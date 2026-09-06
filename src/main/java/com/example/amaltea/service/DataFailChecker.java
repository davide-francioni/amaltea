package com.example.amaltea.service;

import com.example.amaltea.model.evaluation.DataFailReport;
import com.example.amaltea.model.execution.CapraRunResult;
import com.example.amaltea.model.process.CapraStandardOutput;
import com.example.amaltea.model.process.ReferenceVariance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Check F2: did the run find the same things as the reference?
 * <p>
 * <b>F2 never compares prose.</b> Descriptions, recommendations, feature evidence and
 * traceability references are free text produced by the model: a smaller model will not
 * use the same words, and comparing them literally would fail on every run, making the
 * criterion useless. Only the structured, enumerable parts are compared; the quality of
 * the narrative text is F3's business.
 * <p>
 * Three fields are excluded for cause: the timestamp, set on every execution; the issue
 * ids, which the consolidating agent reassigns by arrival order rather than by identity
 * of the problem, so two runs finding the same defects in a different order get different
 * ids; and the confidence score, a model's self-assessment.
 * <p>
 * The report is navigated as a generic tree rather than deserialized into types mirrored
 * from CAPRA. AMALTEA only has to <em>compare</em> that output, so a mirrored copy would
 * be six more classes and a second definition to keep aligned by hand.
 */
@Service
public class DataFailChecker {

    // Field names of CAPRA's report, gathered here so a change on that side is a change
    // in one place rather than a hunt through string literals.
    private static final String ISSUES = "issues";
    private static final String FEATURE_COVERAGE = "featureCoverage";
    private static final String TRACEABILITY = "traceabilityMatrix";
    private static final String FEATURE_NAME = "featureName";
    private static final String STATUS = "status";
    private static final String USE_CASE_ID = "useCaseId";
    private static final String HAS_DESIGN = "hasDesign";
    private static final String HAS_TEST = "hasTest";
    private static final String QUOTE = "quote";
    private static final String CATEGORY = "category";
    private static final String PAGE = "pageReference";
    private static final String SEVERITY = "severity";

    private final ObjectMapper mapper = ReferenceArchive.archiveMapper();

    /**
     * Compares a run against its reference.
     *
     * @param variance the noise floor measured across repetitions of the reference;
     *                 divergences on fields that already varied there are absorbed rather
     *                 than counted, because they are not evidence about the model
     */
    public DataFailReport check(CapraRunResult result,
                                CapraStandardOutput standard,
                                ReferenceVariance variance) {

        if (result.auditReportJson() == null || standard.auditReportJson() == null) {
            return new DataFailReport(0, 0, 0, 0, List.of(), 0, Map.of(), 0,
                    List.of("report assente"), List.of(), List.of(), false, true);
        }

        JsonNode actual;
        JsonNode expected;
        try {
            actual = mapper.readTree(result.auditReportJson());
            expected = mapper.readTree(standard.auditReportJson());
        } catch (Exception e) {
            return new DataFailReport(0, 0, 0, 0, List.of(), 0, Map.of(), 0,
                    List.of("report non interpretabile"), List.of(e.getMessage()),
                    List.of(), false, true);
        }

        Set<String> unstable = variance == null || variance.unstableFields() == null
                ? Set.of() : new HashSet<>(variance.unstableFields());
        List<String> ignored = new ArrayList<>();
        List<String> diffs = new ArrayList<>();

        // ── 1. Feature coverage: the cleanest signal ──────────────────────────────
        // Aligned on the feature name, which comes from the knowledge base and not from
        // the model: identical by construction across runs. A vector of categorical
        // labels already paired up, so agreement is exact — no fuzzy matching involved.
        Map<String, String> expectedFeatures = statusByFeature(expected);
        Map<String, String> actualFeatures = statusByFeature(actual);
        int featureTotal = expectedFeatures.size();
        int featureMatches = 0;
        for (Map.Entry<String, String> entry : expectedFeatures.entrySet()) {
            String observed = actualFeatures.get(entry.getKey());
            if (entry.getValue().equals(observed)) {
                featureMatches++;
            } else if (unstable.contains(FEATURE_COVERAGE + "." + entry.getKey())) {
                featureMatches++;
                ignored.add("feature " + entry.getKey() + " (instabile nel riferimento)");
            } else {
                diffs.add("feature %s: atteso %s, osservato %s"
                        .formatted(entry.getKey(), entry.getValue(), observed));
            }
        }

        // ── 2. Traceability: sets first, then flags over the intersection ─────────
        Map<String, String> expectedUc = flagsByUseCase(expected);
        Map<String, String> actualUc = flagsByUseCase(actual);
        List<String> missingUc = new ArrayList<>();
        int traceMatches = 0;
        for (Map.Entry<String, String> entry : expectedUc.entrySet()) {
            String observed = actualUc.get(entry.getKey());
            if (observed == null) {
                missingUc.add(entry.getKey());
            } else if (entry.getValue().equals(observed)) {
                traceMatches++;
            } else {
                diffs.add("caso d'uso %s: atteso %s, osservato %s"
                        .formatted(entry.getKey(), entry.getValue(), observed));
            }
        }

        // ── 3. Issues: aggregate, then paired on the anchored quote ──────────────
        // The id cannot serve as a key, so pairing uses the quote: it is text extracted
        // from the document and anchored to it, so two runs that spot the same problem
        // cite the same passage. Falls back to category and page when it does not match.
        List<JsonNode> expectedIssues = list(expected.path(ISSUES));
        List<JsonNode> actualIssues = list(actual.path(ISSUES));
        Set<String> actualKeys = new HashSet<>();
        Set<String> actualFallback = new HashSet<>();
        for (JsonNode issue : actualIssues) {
            actualKeys.add(normalise(issue.path(QUOTE).asText("")));
            actualFallback.add(fallbackKey(issue));
        }

        Map<String, Integer> missingBySeverity = new LinkedHashMap<>();
        int matchedIssues = 0;
        for (JsonNode issue : expectedIssues) {
            boolean found = actualKeys.contains(normalise(issue.path(QUOTE).asText("")))
                    || actualFallback.contains(fallbackKey(issue));
            if (found) {
                matchedIssues++;
            } else {
                String severity = issue.path(SEVERITY).asText("UNKNOWN");
                missingBySeverity.merge(severity, 1, Integer::sum);
                diffs.add("rilievo mancante (%s) a pagina %s"
                        .formatted(severity, issue.path(PAGE).asText("?")));
            }
        }

        int countDelta = actualIssues.size() - expectedIssues.size();
        int extra = Math.max(0, actualIssues.size() - matchedIssues);

        boolean anyDivergence = featureMatches < featureTotal
                || !missingUc.isEmpty()
                || traceMatches < expectedUc.size() - missingUc.size()
                || !missingBySeverity.isEmpty();

        // The campaign runs with no tolerance. The measurements are kept so that, should
        // a graduated threshold be agreed later, it can be applied to results already
        // collected instead of requiring the campaign to be run again.
        boolean withinTolerance = !anyDivergence;

        return new DataFailReport(
                featureMatches, featureTotal,
                traceMatches, expectedUc.size(), missingUc,
                countDelta, missingBySeverity, extra,
                List.of(), diffs, ignored,
                withinTolerance, anyDivergence);
    }

    private Map<String, String> statusByFeature(JsonNode report) {
        Map<String, String> map = new HashMap<>();
        for (JsonNode node : list(report.path(FEATURE_COVERAGE))) {
            map.put(node.path(FEATURE_NAME).asText(""), node.path(STATUS).asText(""));
        }
        return map;
    }

    private Map<String, String> flagsByUseCase(JsonNode report) {
        Map<String, String> map = new HashMap<>();
        for (JsonNode node : list(report.path(TRACEABILITY))) {
            map.put(node.path(USE_CASE_ID).asText(""),
                    node.path(HAS_DESIGN).asBoolean(false) + "/" + node.path(HAS_TEST).asBoolean(false));
        }
        return map;
    }

    private String fallbackKey(JsonNode issue) {
        return issue.path(CATEGORY).asText("") + "#" + issue.path(PAGE).asInt(-1);
    }

    /** Case and spacing are not evidence: only the wording of the cited passage is. */
    private String normalise(String quote) {
        return quote == null ? "" : quote.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }
}
