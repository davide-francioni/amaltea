package com.example.amaltea.model.process;

/** Lifecycle of a single candidate configuration tested against the whole corpus. */
public enum TrialStatus {

    /** Queued, VMs not requested yet. */
    PENDING,

    /** Terraform is creating the VMs and loading the model weights. */
    PROVISIONING,

    /** Running CAPRA over the test documents. */
    RUNNING,

    /** Every document was processed and a verdict was produced. */
    COMPLETED,

    /**
     * Stopped early by the fail-fast rule: the configuration failed on the first documents,
     * so the remaining runs and the VM time were not spent. Kept distinct from
     * {@link #COMPLETED} because otherwise an aborted trial would be indistinguishable from
     * one that legitimately ran on few documents.
     */
    ABORTED_EARLY,

    /** Could not be evaluated — typically a provisioning failure, not a model failure. */
    FAILED
}
