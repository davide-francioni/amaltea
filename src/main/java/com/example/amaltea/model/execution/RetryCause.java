package com.example.amaltea.model.execution;

/**
 * Why a single LLM attempt failed.
 * <p>
 * This classification is what makes it acceptable to have disabled Spring AI's transport
 * retry: without it, a network blip would be recorded as «this SLM cannot do it», which is
 * exactly the kind of error that invalidates a verdict. It is not a metric — it is part of
 * the verdict's validity.
 */
public enum RetryCause {

    /** The endpoint was unreachable. Infrastructure, not the model. */
    CONNECTION_ERROR,

    /** No response within the per-request budget. Could be either — read with the model size. */
    TIMEOUT,

    /** The server answered with an error status. On a local vLLM this usually means OOM or crash. */
    HTTP_ERROR,

    /** A response arrived but could not be parsed into the expected structure. The model's fault. */
    PARSE_ERROR
}
