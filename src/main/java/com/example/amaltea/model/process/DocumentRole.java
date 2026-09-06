package com.example.amaltea.model.process;

/**
 * Whether a test document is used for evaluation or for training.
 * <p>
 * Only relevant at {@link com.example.amaltea.model.InterventionLevel#L3_FINE_TUNING}:
 * training and evaluating on the same documents would invalidate the result. At levels
 * L0–L2 every document is {@link #EVALUATION}.
 */
public enum DocumentRole {

    /** Used to measure whether the configuration reproduces CAPRA's standard output. */
    EVALUATION,

    /** Used to train a fine-tuning adapter, and therefore excluded from evaluation. */
    TRAINING
}
