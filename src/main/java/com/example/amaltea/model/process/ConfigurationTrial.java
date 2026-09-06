package com.example.amaltea.model.process;

import com.example.amaltea.model.LlmRole;
import com.example.amaltea.model.blueprint.Blueprint;
import com.example.amaltea.model.catalog.SlmModel;
import com.example.amaltea.model.infrastructure.VmInstance;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One candidate configuration, tested against the whole corpus.
 * <p>
 * The middle of the three-level hierarchy. <b>A trial is not a single model:</b> the chosen
 * architecture is «N SLMs per agent», so a trial carries an assignment of models to roles.
 * In the basic descent that assignment is uniform, but the structure has to allow mixed ones
 * — otherwise the central hypothesis of the thesis could not even be represented.
 * <p>
 * The VMs are aggregated here because their lifecycle wraps the loop over documents:
 * provisioned once for the configuration, used for every document, then destroyed. Keeping
 * them recorded is what makes the cost figure verifiable afterwards — without it, the GPU
 * type and hourly rate would be lost.
 */
public class ConfigurationTrial {

    private final String id;
    private final Map<LlmRole, SlmModel> modelAssignment;
    private final Blueprint blueprint;

    private final List<BenchmarkRun> runs = new ArrayList<>();
    private final List<VmInstance> provisionedVms = new ArrayList<>();

    private TrialStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private TrialVerdict verdict;

    public ConfigurationTrial(String id,
                              Map<LlmRole, SlmModel> modelAssignment,
                              Blueprint blueprint) {
        this.id = id;
        this.modelAssignment = Map.copyOf(modelAssignment);
        this.blueprint = blueprint;
        this.status = TrialStatus.PENDING;
    }

    /** Marks the start of VM creation. */
    public void startProvisioning() {
        this.status = TrialStatus.PROVISIONING;
        this.startedAt = LocalDateTime.now();
    }

    /** Records the machines created for this trial and moves on to execution. */
    public void vmsReady(List<VmInstance> vms) {
        this.provisionedVms.addAll(vms);
        this.status = TrialStatus.RUNNING;
    }

    public void addRun(BenchmarkRun run) {
        this.runs.add(run);
    }

    /** Closes the trial after every document was processed. */
    public void complete(TrialVerdict verdict) {
        this.verdict = verdict;
        this.status = TrialStatus.COMPLETED;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * Closes the trial cut short by the fail-fast rule. Kept distinct from
     * {@link #complete} so that an aborted trial is never mistaken for one that
     * legitimately ran on few documents.
     */
    public void abortEarly(TrialVerdict verdict) {
        this.verdict = verdict;
        this.status = TrialStatus.ABORTED_EARLY;
        this.finishedAt = LocalDateTime.now();
    }

    /** Closes the trial as unevaluable — typically a provisioning failure. */
    public void fail() {
        this.status = TrialStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
    }

    /** How many documents failed so far, for the fail-fast check. */
    public long failedSoFar() {
        return runs.stream().filter(r -> r.status() == RunStatus.COMPLETED && !r.passed()).count();
    }

    public String id() { return id; }
    public Map<LlmRole, SlmModel> modelAssignment() { return modelAssignment; }
    public Blueprint blueprint() { return blueprint; }
    public List<BenchmarkRun> runs() { return List.copyOf(runs); }
    public List<VmInstance> provisionedVms() { return List.copyOf(provisionedVms); }
    public TrialStatus status() { return status; }
    public LocalDateTime startedAt() { return startedAt; }
    public LocalDateTime finishedAt() { return finishedAt; }
    public TrialVerdict verdict() { return verdict; }
}
