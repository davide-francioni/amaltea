package com.example.amaltea.model.execution;

import java.util.List;

/**
 * Everything one CAPRA execution produced, from a single call.
 * <p>
 * CAPRA exposes two older endpoints, and neither gives what the three checks need — one
 * returns the compiled document without the structured report, the other the report
 * without compiling anything. Calling both would run the models twice on the same
 * document: double cost, and without guaranteed determinism two outputs that need not
 * agree with each other.
 * <p>
 * This record mirrors the payload of CAPRA's unified endpoint, and it is what allows a
 * document to be processed <em>once</em>.
 *
 * @param httpStatus      response status; anything but 200 is a hard failure
 * @param auditReportJson the structured report, kept as raw text. Check F2 navigates it
 *                        as a generic tree rather than deserializing it into mirrored
 *                        types: AMALTEA only has to <em>compare</em> that output, not
 *                        build or manipulate it
 * @param texGenerated    whether the LaTeX role produced a usable source
 * @param pdfGenerated    whether the source compiled
 * @param latexSource     the generated source, returned inline by CAPRA. Held as text
 *                        rather than as a path because the file lives in a directory
 *                        CAPRA manages for itself, which fills up on every run
 * @param timedOut        whether no answer arrived within the run budget. Detected on
 *                        this side: CAPRA cannot report a timeout it never noticed
 * @param retryEvents     every failed attempt, with its cause
 * @param compileError    diagnostic detail when compilation failed; it explains, it does
 *                        not decide
 */
public record CapraRunResult(
        int httpStatus,
        String auditReportJson,
        boolean texGenerated,
        boolean pdfGenerated,
        String latexSource,
        boolean timedOut,
        List<RetryEvent> retryEvents,
        String compileError
) {
    /** A run that never reached CAPRA, or whose answer never arrived. */
    public static CapraRunResult failed(int httpStatus, boolean timedOut, String detail) {
        return new CapraRunResult(httpStatus, null, false, false, null, timedOut, List.of(), detail);
    }

    /** Failed attempts attributable to the infrastructure rather than to the model. */
    public long infrastructuralRetries() {
        return retryEvents == null ? 0
                : retryEvents.stream().filter(RetryEvent::isInfrastructural).count();
    }
}
