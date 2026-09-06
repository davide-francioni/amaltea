package com.example.amaltea.model.blueprint;

/**
 * How a {@link RoleConfig} should be turned into a chat client.
 * <p>
 * The third value is the one that matters for the campaign: vLLM exposes an
 * OpenAI-compatible API, so the same Spring AI client works against a local endpoint by
 * changing only the base URL. No new integration is needed.
 */
public enum Provider {

    /** The real OpenAI API. Used by the baseline blueprint. */
    OPENAI,

    /** The real Anthropic API. Used by the baseline blueprint for the LaTeX role. */
    ANTHROPIC,

    /** A local server speaking the OpenAI protocol — vLLM in this project. */
    OPENAI_COMPATIBLE
}
