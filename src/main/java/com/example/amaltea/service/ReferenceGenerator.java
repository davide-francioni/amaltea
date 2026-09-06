package com.example.amaltea.service;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.blueprint.Blueprint;
import com.example.amaltea.model.execution.CapraInstance;
import com.example.amaltea.model.execution.CapraRunResult;
import com.example.amaltea.model.process.CapraStandardOutput;
import com.example.amaltea.model.process.ReferenceVariance;
import com.example.amaltea.model.process.TestDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces the reference outputs against which every later run is compared.
 * <p>
 * The reference is generated with the <b>refactored</b> system in its original
 * configuration, never with the version preceding the refactoring. Otherwise the
 * comparison would set «original code with commercial models» against «modified code with
 * small models», and an observed difference could come from the modifications rather than
 * from the model.
 * <p>
 * Each document is processed <b>more than once</b>. The determinism parameters available
 * do not guarantee identical executions — the seed offered by commercial providers is
 * explicitly best-effort — so the reference itself carries a residual variability.
 * Measuring it yields a noise floor: a divergence smaller than the spread between
 * repetitions of the same configuration is not evidence about anything.
 */
@Service
public class ReferenceGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReferenceGenerator.class);

    private final CapraRunner runner;
    private final ReferenceArchive archive;
    private final BlueprintGenerator blueprintGenerator;
    private final AmalteaProperties properties;
    private final ObjectMapper mapper = ReferenceArchive.archiveMapper();

    public ReferenceGenerator(CapraRunner runner,
                              ReferenceArchive archive,
                              BlueprintGenerator blueprintGenerator,
                              AmalteaProperties properties) {
        this.runner = runner;
        this.archive = archive;
        this.blueprintGenerator = blueprintGenerator;
        this.properties = properties;
    }

    /**
     * Generates and archives the reference for one document, repeating the execution.
     * <p>
     * A single instance of the system serves all the repetitions: the configuration does
     * not change between them, so restarting would only add minutes without buying any
     * isolation that matters here.
     */
    public List<CapraStandardOutput> generate(TestDocument document,
                                              Blueprint baseline,
                                              Path blueprintFile,
                                              int repetitions) {
        List<CapraStandardOutput> outputs = new ArrayList<>();
        CapraInstance instance = runner.start(baseline, blueprintFile);
        try {
            for (int i = 1; i <= repetitions; i++) {
                log.info("Riferimento per '{}': ripetizione {}/{}",
                        document.filename(), i, repetitions);

                CapraRunResult result = runner.analyze(document);
                if (result.auditReportJson() == null) {
                    // The reference is produced with frontier models: a failure here is
                    // an infrastructure problem, not a result. Stopping is right —
                    // continuing would build a noise floor out of broken runs.
                    throw new IllegalStateException(
                            "Generazione del riferimento fallita per '" + document.filename()
                                    + "' alla ripetizione " + i + ": " + result.compileError());
                }

                CapraStandardOutput output = new CapraStandardOutput(
                        document.id(), i,
                        result.auditReportJson(),
                        issueCount(result.auditReportJson()),
                        LocalDateTime.now(),
                        baseline.id());

                archive.save(output);
                outputs.add(output);
            }
        } finally {
            runner.stop(instance);
        }

        ReferenceVariance variance = computeVariance(outputs);
        archive.saveVariance(variance);

        log.info("Riferimento per '{}' completato: {} rilievi, oscillazione {}",
                document.filename(),
                outputs.getFirst().issueCount(),
                variance.issueCountRange());

        return outputs;
    }

    /**
     * Measures how much the reference varies between repetitions.
     * <p>
     * Two quantities are recorded: the spread in the number of findings, and which fields
     * changed at all. The second is the more useful of the two — a field that moved
     * between two runs of the <em>same</em> model cannot be used as evidence against a
     * different one, so check F2 absorbs divergences there instead of counting them.
     */
    public ReferenceVariance computeVariance(List<CapraStandardOutput> outputs) {
        if (outputs.isEmpty()) {
            return new ReferenceVariance("", 0, 0, List.of());
        }
        String documentId = outputs.getFirst().documentId();

        int min = outputs.stream().mapToInt(CapraStandardOutput::issueCount).min().orElse(0);
        int max = outputs.stream().mapToInt(CapraStandardOutput::issueCount).max().orElse(0);

        return new ReferenceVariance(documentId, outputs.size(), max - min,
                unstableFields(outputs));
    }

    /**
     * Whether the stored reference is still comparable with what the system would produce
     * now.
     * <p>
     * References stop being valid when the system changes underneath them: a refactoring,
     * or an edit to the knowledge base, makes them incomparable — and every later
     * divergence would be blamed on the model. Checking costs nothing when they are
     * valid, and guards against an error that would otherwise leave no trace.
     */
    public boolean isStillValid(String documentId, Blueprint baseline) {
        return archive.isStillValid(documentId, baseline.id());
    }

    /**
     * Fields whose value differed between repetitions of the same configuration.
     * <p>
     * Only feature coverage is inspected: it is the one part of the report aligned on a
     * key the model does not control, so a difference there is unambiguous. The findings
     * would need pairing before they could be compared, and pairing is itself
     * approximate — the noise floor has to rest on something exact.
     */
    private List<String> unstableFields(List<CapraStandardOutput> outputs) {
        if (outputs.size() < 2) {
            return List.of();
        }
        Set<String> unstable = new HashSet<>();
        try {
            JsonNode first = mapper.readTree(outputs.getFirst().auditReportJson());
            for (int i = 1; i < outputs.size(); i++) {
                JsonNode other = mapper.readTree(outputs.get(i).auditReportJson());
                compareFeatureCoverage(first, other, unstable);
            }
        } catch (Exception e) {
            log.warn("Impossibile calcolare i campi instabili: {}", e.getMessage());
        }
        return List.copyOf(unstable);
    }

    private void compareFeatureCoverage(JsonNode a, JsonNode b, Set<String> unstable) {
        for (JsonNode featureA : a.path("featureCoverage")) {
            String name = featureA.path("featureName").asText("");
            for (JsonNode featureB : b.path("featureCoverage")) {
                if (name.equals(featureB.path("featureName").asText(""))
                        && !featureA.path("status").asText("")
                        .equals(featureB.path("status").asText(""))) {
                    unstable.add("featureCoverage." + name);
                }
            }
        }
    }

    private int issueCount(String reportJson) {
        try {
            return mapper.readTree(reportJson).path("issues").size();
        } catch (Exception e) {
            return 0;
        }
    }
}
