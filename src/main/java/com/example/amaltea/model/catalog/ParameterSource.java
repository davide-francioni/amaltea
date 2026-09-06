package com.example.amaltea.model.catalog;

/**
 * How the parameter count of a model was obtained.
 * <p>
 * The descent is ordered by parameter count, so the quality of that number matters. The
 * values below are the cascade, in decreasing order of authority: a count computed from the
 * architecture and one guessed from the model name do not carry the same evidential weight,
 * and the thesis should be able to say which is which rather than presenting them as
 * uniformly certain.
 * <p>
 * If none of the four works, the model is discarded and the reason recorded.
 */
public enum ParameterSource {

    /**
     * Metadata exposed by the Hugging Face API, derived from the actual weight files.
     * Authoritative when present; absent on GGUF-only repositories.
     */
    SAFETENSORS_METADATA,

    /**
     * Computed analytically from {@code config.json}: hidden size, layer count, vocabulary,
     * FFN intermediate size, and for MoE the expert count and experts-per-token. This is
     * arithmetic on the real architecture, not an estimate — and it handles MoE correctly.
     */
    CONFIG_JSON_COMPUTED,

    /** Weight file sizes divided by bytes-per-parameter. Rough; useful as a sanity check. */
    FILE_SIZE_ESTIMATE,

    /** Parsed from the model name. Last resort; brittle, and wrong on MoE. Low confidence. */
    NAME_PARSING
}
