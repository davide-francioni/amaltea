package com.example.amaltea.model.process;

import com.example.amaltea.model.evaluation.EvaluationVerdict;
import com.example.amaltea.model.execution.CapraRunResult;
import com.example.amaltea.model.metrics.RunMetrics;

import java.time.LocalDateTime;

/**
 * One execution: one configuration on one document.
 * <p>
 * The bottom of the three-level hierarchy
 * {@code BenchmarkProcess → ConfigurationTrial → BenchmarkRun}.
 * <p>
 * A run owns three products: the raw result, the metrics and the verdict. The raw result is
 * kept — not only the verdict — because it holds the JSON the SLM actually produced, which
 * is the material the verdict was computed from. Without it a judgement could never be
 * revisited without re-running everything.
 * <p>
 * Mutable rather than a record: it acquires its outcome in stages as the run progresses.
 */
public class BenchmarkRun {

    private final String id;
    private final TestDocument document;

    private RunStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private CapraRunResult result;
    private RunMetrics metrics;
    private EvaluationVerdict verdict;

    public BenchmarkRun(String id, TestDocument document) {
        this.id = id;
        this.document = document;
        this.status = RunStatus.PENDING;
    }

    /** Marks the run as started. */
    public void start() {
        this.status = RunStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    /** Attaches the raw CAPRA output and moves on to evaluation. */
    public void recordResult(CapraRunResult result, RunMetrics metrics) {
        this.result = result;
        this.metrics = metrics;
        this.status = RunStatus.EVALUATING;
    }

    /** Attaches the verdict and closes the run. */
    public void complete(EvaluationVerdict verdict) {
        this.verdict = verdict;
        this.status = RunStatus.COMPLETED;
        this.finishedAt = LocalDateTime.now();
    }

    /** Closes the run as failed on AMALTEA's side — not a CAPRA failure. */
    public void fail() {
        this.status = RunStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
    }

    /** Whether this run counts as a pass. A run without a verdict does not. */
    public boolean passed() {
        return verdict != null && verdict.passed();
    }

    public String id() { return id; }
    public TestDocument document() { return document; }
    public RunStatus status() { return status; }
    public LocalDateTime startedAt() { return startedAt; }
    public LocalDateTime finishedAt() { return finishedAt; }
    public CapraRunResult result() { return result; }
    public RunMetrics metrics() { return metrics; }
    public EvaluationVerdict verdict() { return verdict; }
}
