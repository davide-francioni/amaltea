package com.example.amaltea.controller;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.InterventionLevel;
import com.example.amaltea.model.catalog.CatalogFilterCriteria;
import com.example.amaltea.model.catalog.SlmCatalog;
import com.example.amaltea.model.process.BenchmarkProcess;
import com.example.amaltea.model.process.DocumentRole;
import com.example.amaltea.model.process.TestDocument;
import com.example.amaltea.model.process.TrialVerdict;
import com.example.amaltea.service.BenchmarkOrchestrator;
import com.example.amaltea.service.ProcessArchive;
import com.example.amaltea.service.SlmCatalogBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Starts and inspects benchmark campaigns.
 * <p>
 * No transport objects: domain objects are returned directly. Separating an API from a
 * model earns its keep when several clients are outside one's control; here the consumer
 * is one and the same hands maintain both, so mirrored records would be boilerplate to
 * keep aligned for no gain.
 * <p>
 * No logic either. The controller turns a request into a service call and returns the
 * result — if a condition on a trial outcome ever appeared here, it would belong to the
 * orchestrator.
 */
@RestController
@RequestMapping("/api/processes")
public class ProcessController {

    private static final Logger log = LoggerFactory.getLogger(ProcessController.class);

    private final BenchmarkOrchestrator orchestrator;
    private final SlmCatalogBuilder catalogBuilder;
    private final ProcessArchive archive;
    private final AmalteaProperties properties;

    public ProcessController(BenchmarkOrchestrator orchestrator,
                             SlmCatalogBuilder catalogBuilder,
                             ProcessArchive archive,
                             AmalteaProperties properties) {
        this.orchestrator = orchestrator;
        this.catalogBuilder = catalogBuilder;
        this.archive = archive;
        this.properties = properties;
    }

    /**
     * Starts a campaign.
     *
     * @param inheritCatalogFrom an earlier campaign whose candidate set this one should
     *                           reuse. Discovery is fresh by default, which keeps the
     *                           landscape current; but the central result of the work is
     *                           the frontier across intervention levels, and comparing
     *                           the threshold of one level with another requires that
     *                           both explored the same candidates. Left to chance, two
     *                           campaigns run months apart could differ for that reason
     *                           alone, with nothing to signal it
     */
    @PostMapping
    public ResponseEntity<BenchmarkProcess> startProcess(
            @RequestParam String name,
            @RequestParam InterventionLevel level,
            @RequestBody CatalogFilterCriteria criteria,
            @RequestParam(required = false) String inheritCatalogFrom) {

        SlmCatalog catalog = inheritCatalogFrom == null
                ? catalogBuilder.build(criteria)
                : archive.loadCatalog(inheritCatalogFrom);

        BenchmarkProcess process = new BenchmarkProcess(
                "process-" + UUID.randomUUID(), name, level, loadCorpus(), catalog);

        log.info("Avvio campagna '{}' a livello {} — {}", name, level, catalog.coverageSummary());

        // Synchronous for now: a campaign lasts hours, so this eventually belongs on a
        // background executor with the response returning immediately.
        orchestrator.runProcess(process);

        return ResponseEntity.ok(process);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BenchmarkProcess> getProcess(@PathVariable String id) {
        return ResponseEntity.ok(archive.load(id));
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<List<TrialVerdict>> getResults(@PathVariable String id) {
        List<TrialVerdict> verdicts = archive.load(id).trials().stream()
                .map(trial -> trial.verdict())
                .filter(java.util.Objects::nonNull)
                .toList();
        return ResponseEntity.ok(verdicts);
    }

    @GetMapping
    public ResponseEntity<List<String>> listProcesses() {
        return ResponseEntity.ok(archive.listProcesses());
    }

    /** The test corpus, read from the configured folder. */
    private List<TestDocument> loadCorpus() {
        Path dataset = properties.datasetPath();
        List<TestDocument> documents = new ArrayList<>();
        try (var files = Files.list(dataset)) {
            files.filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .forEach(path -> {
                        String filename = path.getFileName().toString();
                        documents.add(new TestDocument(
                                filename.replaceAll("\\.pdf$", ""),
                                filename, path, DocumentRole.EVALUATION));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile leggere il corpus da " + dataset, e);
        }
        if (documents.isEmpty()) {
            throw new IllegalStateException("Nessun documento trovato in " + dataset);
        }
        return documents;
    }
}
