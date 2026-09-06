package com.example.amaltea.model;

/**
 * How much help the SLMs are given, beyond simply replacing the model.
 * <p>
 * This is the second axis of the research, alongside model size. The methodological
 * constraint is that the size descent happens <em>within</em> one level and never across
 * levels: rewriting prompts while shrinking the model makes it impossible to attribute a
 * failure to either. The level is therefore fixed per {@code BenchmarkProcess} and written
 * into every blueprint that process generates.
 * <p>
 * The result of the thesis is a frontier per level: the distance between the L0 threshold
 * and the L3 threshold is the measure of what the intervention is worth.
 */
public enum InterventionLevel {

    /**
     * Substitution only. Prompts, pipeline and thresholds stay exactly as CAPRA was validated.
     * Claim if it holds: SLMs replace frontier models with no adaptation at all.
     */
    L0_SOSTITUZIONE,

    /**
     * Prompts rewritten for small models — more explicit instructions, examples, stricter
     * format. Code unchanged.
     * Claim if it holds: SLMs are enough, but they must be instructed differently.
     */
    L1_PROMPTING,

    /**
     * CAPRA's pipeline adapted: tasks decomposed into smaller steps, output validation and
     * repair, targeted retries.
     * Claim if it holds: SLMs are enough, but the system must be designed around their limits.
     */
    L2_ADATTAMENTO_CAPRA,

    /**
     * Lightweight adapters (LoRA / QLoRA) trained per role on CAPRA's standard outputs.
     * Claim if it holds: generic SLMs no, specialised ones yes.
     */
    L3_FINE_TUNING
}
