package com.example.amaltea.model.blueprint;

/**
 * How many times a failed LLM call is retried, and at which layer.
 * <p>
 * CAPRA has two retry layers stacked: {@code ResilientLlmCaller} at the application level,
 * and Spring AI's transport retry underneath. Left as they are, one logical call could
 * become up to six real HTTP requests.
 * <p>
 * <b>Decision for benchmark mode: Spring AI's retry is disabled.</b> The transport retry
 * absorbs infrastructure problems — a VM not yet ready, a network blip — and reports them
 * as successes, which is precisely what has to stay distinct from the model being unable to
 * do the job. What remains is three application-level attempts, each logged with a
 * classified cause.
 * <p>
 * This is a blueprint field rather than a constant so that the choice is archived with the
 * run instead of hidden in the code.
 *
 * @param springAiEnabled        transport-level retry; {@code false} in benchmark mode
 * @param applicationMaxAttempts total attempts by {@code ResilientLlmCaller}, including the first
 */
public record RetryPolicy(
        boolean springAiEnabled,
        int applicationMaxAttempts
) {
    /** The benchmark default: no transport retry, three application attempts. */
    public static RetryPolicy benchmarkDefault() {
        return new RetryPolicy(false, 3);
    }
}
