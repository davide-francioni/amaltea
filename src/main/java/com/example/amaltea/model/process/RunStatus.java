package com.example.amaltea.model.process;

/** Lifecycle of a single execution: one configuration on one document. */
public enum RunStatus {

    /** Queued. */
    PENDING,

    /** CAPRA is analysing the document. */
    RUNNING,

    /** CAPRA returned; the three checks are being applied. */
    EVALUATING,

    /** Evaluated, with a verdict attached. */
    COMPLETED,

    /** Could not produce a verdict — an error on AMALTEA's side, not a CAPRA failure. */
    FAILED
}
