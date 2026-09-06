package com.example.amaltea.model.process;

/**
 * Aggregate outcome of one configuration over the whole corpus.
 * <p>
 * The descent decides on <em>this</em>, not on individual runs: the question it answers is
 * «does this configuration pass?», and that is only meaningful across all the documents.
 * <p>
 * How many failed documents sink a configuration is still to be agreed with the supervisor,
 * so {@code passed} is recorded alongside the raw counts rather than derived from a rule
 * fixed here.
 *
 * @param documentsPassed how many documents the configuration got right
 * @param documentsFailed how many it failed
 * @param passed          the aggregate verdict
 * @param stoppedEarly    whether the fail-fast rule cut the trial short. Marked explicitly
 *                        because otherwise an aborted trial would be indistinguishable from
 *                        one that legitimately ran on few documents
 * @param stopReason      why it was cut short, when it was
 */
public record TrialVerdict(
        int documentsPassed,
        int documentsFailed,
        boolean passed,
        boolean stoppedEarly,
        String stopReason
) {
    /** How many documents were actually evaluated. */
    public int documentsEvaluated() {
        return documentsPassed + documentsFailed;
    }
}
