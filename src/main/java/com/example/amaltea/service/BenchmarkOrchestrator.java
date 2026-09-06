package com.example.amaltea.service;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.LlmRole;
import com.example.amaltea.model.blueprint.Blueprint;
import com.example.amaltea.model.catalog.SlmCatalog;
import com.example.amaltea.model.catalog.SlmModel;
import com.example.amaltea.model.evaluation.EvaluationVerdict;
import com.example.amaltea.model.execution.CapraInstance;
import com.example.amaltea.model.execution.CapraRunResult;
import com.example.amaltea.model.infrastructure.VmInstance;
import com.example.amaltea.model.metrics.RunMetrics;
import com.example.amaltea.model.process.BenchmarkProcess;
import com.example.amaltea.model.process.BenchmarkRun;
import com.example.amaltea.model.process.ConfigurationTrial;
import com.example.amaltea.model.process.ReferenceVariance;
import com.example.amaltea.model.process.CapraStandardOutput;
import com.example.amaltea.model.process.TestDocument;
import com.example.amaltea.model.process.TrialVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs a campaign from start to finish.
 * <p>
 * Five phases: the references are prepared, the catalogue is resolved and frozen, the
 * search descends to a threshold, the roles are refined around it, and the optional
 * follow-ups are left for last. Keeping the fifth separate is not organisational tidiness
 * — it makes explicit, and settled in advance, what gets sacrificed first if the time
 * available turns out to be short.
 */
@Service
public class BenchmarkOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkOrchestrator.class);

    private final SlmCatalogBuilder catalogBuilder;
    private final ModelDescentStrategy descent;
    private final ReferenceGenerator references;
    private final BlueprintGenerator blueprints;
    private final TerraformProvisioner provisioner;
    private final CapraRunner runner;
    private final EvaluationService evaluation;
    private final CostCalculator costs;
    private final ProcessArchive archive;
    private final ReferenceArchive referenceArchive;
    private final AmalteaProperties properties;

    public BenchmarkOrchestrator(SlmCatalogBuilder catalogBuilder,
                                 ModelDescentStrategy descent,
                                 ReferenceGenerator references,
                                 BlueprintGenerator blueprints,
                                 TerraformProvisioner provisioner,
                                 CapraRunner runner,
                                 EvaluationService evaluation,
                                 CostCalculator costs,
                                 ProcessArchive archive,
                                 ReferenceArchive referenceArchive,
                                 AmalteaProperties properties) {
        this.catalogBuilder = catalogBuilder;
        this.descent = descent;
        this.references = references;
        this.blueprints = blueprints;
        this.provisioner = provisioner;
        this.runner = runner;
        this.evaluation = evaluation;
        this.costs = costs;
        this.archive = archive;
        this.referenceArchive = referenceArchive;
        this.properties = properties;
    }

    /**
     * Runs one campaign: one intervention level, one descent per admissible family.
     */
    public void runProcess(BenchmarkProcess process) {
        archive.save(process);

        // Resources left behind by an earlier execution would hold accelerators that are
        // still billing, and nothing else would report them.
        provisioner.reapOrphans(properties.archivePath().resolve("terraform"));

        process.startDescent();
        SlmCatalog catalog = process.catalog();

        for (String family : catalog.descendableFamilies()) {
            log.info("Discesa nella famiglia '{}' ({} taglie)",
                    family, catalog.ladderFor(family).size());
            descendFamily(process, catalog, family);
        }

        process.complete();
        archive.save(process);

        if (descent.monotonicityViolated(catalog, catalog.descendableFamilies().getFirst(),
                process.trials())) {
            // Not an error to correct but a finding to report: it would mean the binary
            // search rests on a false premise, and the thesis has to say so.
            log.warn("MONOTONICITÀ VIOLATA: un modello più piccolo ha superato le verifiche "
                    + "dove uno più grande le ha fallite. Da riportare fra i risultati.");
        }
    }

    private void descendFamily(BenchmarkProcess process, SlmCatalog catalog, String family) {
        while (!descent.shouldStop(catalog, family, process.trials())) {
            Optional<SlmModel> candidate = descent.nextUniformCandidate(catalog, family, process.trials());

            Map<LlmRole, SlmModel> assignment = candidate
                    .map(this::uniform)
                    .or(() -> descent.confirmationCandidate(catalog, family, process.trials())
                            .map(this::uniform))
                    .or(() -> descent.nextRefinement(catalog, family, process.trials()))
                    .orElse(null);

            if (assignment == null) {
                return;
            }

            ConfigurationTrial trial = new ConfigurationTrial(
                    "trial-" + UUID.randomUUID(), assignment, null);
            process.addTrial(trial);
            runTrial(process, trial);
            archive.saveTrial(process.id(), trial);
        }
    }

    /**
     * Evaluates one configuration over the corpus.
     * <p>
     * The machines are created once and used for every document: provisioning costs
     * minutes, and repeating it per document would be a large waste. Teardown happens
     * unconditionally, because an exception here would otherwise leave accelerators
     * running.
     */
    public TrialVerdict runTrial(BenchmarkProcess process, ConfigurationTrial trial) {
        Path workDir = properties.archivePath()
                .resolve("terraform").resolve(trial.id());

        trial.startProvisioning();
        List<VmInstance> vms = List.of();
        CapraInstance capra = null;

        try {
            vms = provisioner.provision(trial.modelAssignment(), workDir);
            for (VmInstance vm : vms) {
                if (!provisioner.awaitReady(vm, Duration.ofMinutes(20))) {
                    throw new IllegalStateException("Macchina " + vm.id() + " non pronta");
                }
            }
            trial.vmsReady(vms);

            Map<SlmModel, VmInstance> placement = placement(trial.modelAssignment(), vms);
            Blueprint blueprint = blueprints.generate(
                    trial.modelAssignment(), placement, process.interventionLevel(), null);

            Path blueprintFile = archive.blueprintPath(process.id(), trial.id());
            blueprints.writeYaml(blueprint, blueprintFile);

            capra = runner.start(blueprint, blueprintFile);

            TrialVerdict verdict = evaluateCorpus(process, trial, vms);
            if (verdict.stoppedEarly()) {
                trial.abortEarly(verdict);
            } else {
                trial.complete(verdict);
            }
            return verdict;

        } catch (Exception e) {
            log.error("Trial {} non valutabile: {}", trial.id(), e.getMessage());
            trial.fail();
            return new TrialVerdict(0, 0, false, false, e.getMessage());

        } finally {
            if (capra != null) {
                runner.stop(capra);
            }
            provisioner.destroy(workDir);
        }
    }

    private TrialVerdict evaluateCorpus(BenchmarkProcess process,
                                        ConfigurationTrial trial,
                                        List<VmInstance> vms) {
        int passed = 0;
        int failed = 0;

        for (TestDocument document : process.evaluationDocuments()) {
            BenchmarkRun run = new BenchmarkRun("run-" + UUID.randomUUID(), document);
            trial.addRun(run);
            run.start();

            Instant started = Instant.now();
            CapraRunResult result = runner.analyze(document);
            long processingMillis = Duration.between(started, Instant.now()).toMillis();

            RunMetrics metrics = new RunMetrics(
                    processingMillis,
                    vms.stream().mapToLong(vm -> vm.provisioningTime().toMillis()).max().orElse(0),
                    costs.computeRunCost(vms, process.evaluationDocuments().size()));
            run.recordResult(result, metrics);

            List<CapraStandardOutput> standards = referenceArchive.load(document.id());
            if (standards.isEmpty()) {
                throw new IllegalStateException(
                        "Nessun riferimento archiviato per '" + document.filename() + "'");
            }
            ReferenceVariance variance = referenceArchive.loadVariance(document.id()).orElse(null);

            EvaluationVerdict verdict = evaluation.evaluate(result, standards.getFirst(), variance);
            run.complete(verdict);

            archive.saveRun(process.id(), trial.id(), run);
            archive.saveCapraArtifacts(process.id(), trial.id(), run.id(),
                    result.auditReportJson(), result.latexSource());

            if (verdict.passed()) {
                passed++;
            } else {
                failed++;
            }

            // A configuration already failing on the first documents is interrupted
            // rather than run to the end: the remaining executions would add nothing to
            // the verdict, and the machines are billing throughout.
            if (failed >= properties.failFastThreshold()) {
                return new TrialVerdict(passed, failed, false, true,
                        "fail-fast dopo %d fallimenti".formatted(failed));
            }
        }

        // Whether this counts as a pass is a rule still to be agreed. The individual
        // outcomes are archived, so it can be applied afterwards without re-running.
        return new TrialVerdict(passed, failed, failed == 0, false, null);
    }

    private Map<LlmRole, SlmModel> uniform(SlmModel model) {
        Map<LlmRole, SlmModel> assignment = new EnumMap<>(LlmRole.class);
        for (LlmRole role : LlmRole.values()) {
            assignment.put(role, model);
        }
        return assignment;
    }

    /** Pairs each distinct model with the machine that serves it, in creation order. */
    private Map<SlmModel, VmInstance> placement(Map<LlmRole, SlmModel> assignment,
                                                List<VmInstance> vms) {
        Map<SlmModel, VmInstance> placement = new java.util.LinkedHashMap<>();
        int index = 0;
        for (SlmModel model : blueprints.requiredVms(assignment)) {
            if (index < vms.size()) {
                placement.put(model, vms.get(index++));
            }
        }
        return placement;
    }
}
