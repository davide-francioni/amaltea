package com.example.amaltea.model.execution;

import java.time.LocalDateTime;

/**
 * A running CAPRA process, started with a given blueprint.
 * <p>
 * Named {@code CapraInstance} rather than {@code CapraProcess} to avoid two meanings of the
 * word «process»: here it is an operating-system process, whereas in
 * {@code model.process} it is the benchmark process.
 * <p>
 * Transient: created and destroyed inside the runner, never archived.
 *
 * @param pid       operating-system process id
 * @param port      port the instance listens on
 * @param startedAt when it was launched
 */
public record CapraInstance(
        long pid,
        int port,
        LocalDateTime startedAt
) {}
