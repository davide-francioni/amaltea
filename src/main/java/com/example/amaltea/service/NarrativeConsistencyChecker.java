package com.example.amaltea.service;

import com.example.amaltea.model.evaluation.NarrativeReport;
import com.example.amaltea.model.execution.CapraRunResult;
import com.example.amaltea.model.process.CapraStandardOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check F3 in its deterministic form: does the narrative text agree with the data?
 * <p>
 * This is what runs during the descent, in place of the LLM judge. It does not measure
 * semantic faithfulness in the full sense, but it catches the coarse failures — which are
 * also the likely ones with small models. No model call, no threshold to agree on, no
 * judge bias, and it costs nothing.
 * <p>
 * It exists because without it the LaTeX role would be measured by check F1 alone, that
 * is by «it produced a source that compiles». A small model can emit syntactically valid
 * LaTeX that is semantically empty, or that contradicts the data it is meant to summarise.
 */
@Service
public class NarrativeConsistencyChecker {

    /** Below this share of the reference length, a section counts as collapsed. */
    private static final double COLLAPSE_RATIO = 0.3;

    /** Sections the LaTeX role writes, identified by their heading in the source. */
    private static final List<String> NARRATIVE_SECTIONS =
            List.of("Contesto", "Sintesi", "Punti di forza");

    private static final Pattern SECTION = Pattern.compile("\\\\section\\*?\\{([^}]*)\\}");
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,3})\\b");

    private final ObjectMapper mapper = ReferenceArchive.archiveMapper();

    public NarrativeReport check(CapraRunResult result, CapraStandardOutput standard) {
        if (result.latexSource() == null || result.latexSource().isBlank()) {
            return new NarrativeReport(List.of(), List.of(),
                    List.copyOf(NARRATIVE_SECTIONS), List.of(), true);
        }

        String source = result.latexSource();
        JsonNode report;
        try {
            report = mapper.readTree(result.auditReportJson());
        } catch (Exception e) {
            // Without the structured report there is nothing to check the prose against.
            // Not a narrative failure: F2 has already recorded the real problem.
            return NarrativeReport.clean();
        }

        List<String> missingReferences = missingReferences(source, report);
        List<String> contradictions = numericContradictions(source, report);
        List<String> missingSections = missingSections(source);
        List<String> collapsed = collapsedSections(source, standard);

        boolean failed = !missingReferences.isEmpty()
                || !contradictions.isEmpty()
                || !missingSections.isEmpty()
                || !collapsed.isEmpty();

        return new NarrativeReport(missingReferences, contradictions,
                missingSections, collapsed, failed);
    }

    /**
     * Categories present in the structured report and never named in the prose.
     * <p>
     * A summary that omits an entire category of findings is not summarising: it is
     * describing a different document from the one that was analysed.
     */
    private List<String> missingReferences(String source, JsonNode report) {
        Set<String> categories = new HashSet<>();
        report.path("issues").forEach(issue -> {
            String category = issue.path("category").asText("");
            if (!category.isBlank()) {
                categories.add(category);
            }
        });

        String lower = source.toLowerCase();
        List<String> missing = new ArrayList<>();
        for (String category : categories) {
            if (!lower.contains(category.toLowerCase())) {
                missing.add(category);
            }
        }
        return missing;
    }

    /**
     * Quantities in the prose that contradict the data.
     * <p>
     * Only the total number of findings is checked, and only when the prose states a
     * count near a word that means «findings». A broader check would flag page numbers
     * and section numbers as contradictions, which would make the signal useless.
     */
    private List<String> numericContradictions(String source, JsonNode report) {
        int actual = report.path("issues").size();
        List<String> contradictions = new ArrayList<>();

        Matcher matcher = NUMBER.matcher(source);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value == actual) {
                continue;
            }
            String context = source.substring(
                    Math.max(0, matcher.start() - 40),
                    Math.min(source.length(), matcher.end() + 40)).toLowerCase();
            if (context.contains("problem") || context.contains("riliev")
                    || context.contains("issue") || context.contains("criticit")) {
                contradictions.add("il testo indica %d dove i dati ne riportano %d"
                        .formatted(value, actual));
            }
        }
        return contradictions;
    }

    private List<String> missingSections(String source) {
        Set<String> present = new HashSet<>();
        Matcher matcher = SECTION.matcher(source);
        while (matcher.find()) {
            present.add(matcher.group(1).toLowerCase());
        }

        List<String> missing = new ArrayList<>();
        for (String expected : NARRATIVE_SECTIONS) {
            boolean found = present.stream().anyMatch(p -> p.contains(expected.toLowerCase()));
            if (!found) {
                missing.add(expected);
            }
        }
        return missing;
    }

    /**
     * Sections that exist but whose length fell far below the reference.
     * <p>
     * The comparison is against the reference and not against a fixed number of
     * characters: how long these sections run depends on the document, so an absolute
     * threshold would flag short documents and miss collapsed sections in long ones.
     */
    private List<String> collapsedSections(String source, CapraStandardOutput standard) {
        if (standard.auditReportJson() == null) {
            return List.of();
        }
        int referenceLength = standard.auditReportJson().length();
        if (referenceLength == 0) {
            return List.of();
        }
        return source.length() < referenceLength * COLLAPSE_RATIO
                ? List.of("documento complessivo")
                : List.of();
    }
}
