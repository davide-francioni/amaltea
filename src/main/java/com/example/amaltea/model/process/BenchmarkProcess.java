package com.example.amaltea.model.process;

import com.example.amaltea.model.InterventionLevel;
import com.example.amaltea.model.catalog.SlmCatalog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One benchmark process: one intervention level, one descent through the catalogue.
 * <p>
 * The top of the three-level hierarchy
 * {@code BenchmarkProcess → ConfigurationTrial → BenchmarkRun}.
 * <p>
 * <b>The intervention level is fixed per process, not per execution.</b> That is how the
 * methodological constraint — the descent happens within a level and never across levels —
 * becomes a property of the structure rather than a rule someone has to remember: changing
 * level means opening a new process.
 * <p>
 * The catalogue is aggregated as a frozen snapshot. AMALTEA discovers it by querying a
 * registry, and that landscape changes over time; keeping the snapshot is what makes it
 * possible, later, to compare two processes months apart and say what moved.
 */
public class BenchmarkProcess {

    private final String id;
    private final String name;
    private final InterventionLevel interventionLevel;
    private final LocalDateTime createdAt;

    private final List<TestDocument> documents;
    private final SlmCatalog catalog;
    private final List<ConfigurationTrial> trials = new ArrayList<>();

    private ProcessStatus status;

    public BenchmarkProcess(String id,
                            String name,
                            InterventionLevel interventionLevel,
                            List<TestDocument> documents,
                            SlmCatalog catalog) {
        this.id = id;
        this.name = name;
        this.interventionLevel = interventionLevel;
        this.documents = List.copyOf(documents);
        this.catalog = catalog;
        this.createdAt = LocalDateTime.now();
        this.status = ProcessStatus.CREATED;
    }

    /** The preparatory phase: generating the standard outputs on the corpus. */
    public void startPreparingReferences() {
        this.status = ProcessStatus.PREPARING_REFERENCES;
    }

    /** The descent proper. */
    public void startDescent() {
        this.status = ProcessStatus.RUNNING;
    }

    public void addTrial(ConfigurationTrial trial) {
        this.trials.add(trial);
    }

    public void complete() {
        this.status = ProcessStatus.COMPLETED;
    }

    public void abort() {
        this.status = ProcessStatus.ABORTED;
    }

    /** Documents that count towards evaluation; at L3 the training ones are excluded. */
    public List<TestDocument> evaluationDocuments() {
        return documents.stream().filter(d -> d.role() == DocumentRole.EVALUATION).toList();
    }

    /**
     * The smallest configuration that passed, which is the answer this process produces.
     * Empty if nothing passed — a valid, if negative, result.
     */
    public ConfigurationTrial smallestPassingTrial() {
        return trials.stream()
                .filter(t -> t.verdict() != null && t.verdict().passed())
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public String id() { return id; }
    public String name() { return name; }
    public InterventionLevel interventionLevel() { return interventionLevel; }
    public LocalDateTime createdAt() { return createdAt; }
    public List<TestDocument> documents() { return documents; }
    public SlmCatalog catalog() { return catalog; }
    public List<ConfigurationTrial> trials() { return List.copyOf(trials); }
    public ProcessStatus status() { return status; }
}
