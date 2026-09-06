package com.example.amaltea.model.execution;

import com.example.amaltea.model.LlmRole;

import java.time.Instant;

/**
 * One failed LLM attempt, with its cause classified.
 * <p>
 * Recorded by CAPRA's resilient caller and returned in the run response. This is what
 * makes it acceptable to have disabled the transport-level retry: at analysis time an
 * infrastructure failure can be told apart from the model being unable to do the job.
 * <p>
 * Not a metric. It is not about counting what a run costs — it is about whether the
 * verdict means anything.
 * <p>
 * <b>Mirrors CAPRA's own record field by field</b>, {@code detail} included. The two are
 * separate definitions in separate projects, so a divergence here would surface as a
 * silently dropped field during deserialization rather than as a compilation error.
 *
 * @param role          which role was calling
 * @param attemptNumber 1 for the first attempt
 * @param cause         classified reason
 * @param detail        message from the underlying error, for diagnosis
 * @param timestamp     when the attempt failed
 */
public record RetryEvent(
        LlmRole role,
        int attemptNumber,
        RetryCause cause,
        String detail,
        Instant timestamp
) {
    /** Whether this failure points at the infrastructure rather than at the model. */
    public boolean isInfrastructural() {
        return cause == RetryCause.CONNECTION_ERROR || cause == RetryCause.HTTP_ERROR;
    }
}
