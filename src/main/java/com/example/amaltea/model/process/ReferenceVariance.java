package com.example.amaltea.model.process;

import java.util.List;

/**
 * How much the reference varies between repetitions on the same document — the noise floor.
 * <p>
 * {@code temperature=0} and {@code seed=42} do not guarantee determinism: OpenAI's seed is
 * explicitly best-effort, and on local engines reproducibility depends on the serving
 * configuration. So the reference itself drifts a little from run to run.
 * <p>
 * Without this measure, a divergence observed in an SLM run cannot be told apart from that
 * drift. With it, anything smaller than the spread recorded here is simply not evidence.
 *
 * @param documentId      which document
 * @param repetitions     how many times the reference was generated
 * @param issueCountRange spread between the smallest and largest issue count observed
 * @param unstableFields  fields that differed between repetitions of the <em>same</em>
 *                        configuration, and which therefore cannot be used as evidence
 *                        against an SLM
 */
public record ReferenceVariance(
        String documentId,
        int repetitions,
        int issueCountRange,
        List<String> unstableFields
) {
    /** Whether a difference in issue count is large enough to mean anything. */
    public boolean isSignificant(int observedDelta) {
        return Math.abs(observedDelta) > issueCountRange;
    }
}
