package com.example.amaltea.model.process;

/** Lifecycle of a benchmark process (one intervention level, one descent). */
public enum ProcessStatus {

    /** Created but not started. */
    CREATED,

    /** Generating the CAPRA standard outputs on the test corpus. */
    PREPARING_REFERENCES,

    /** Descending through candidate configurations. */
    RUNNING,

    /** Descent finished: either a threshold was found or the catalogue was exhausted. */
    COMPLETED,

    /** Interrupted before completion. */
    ABORTED
}
