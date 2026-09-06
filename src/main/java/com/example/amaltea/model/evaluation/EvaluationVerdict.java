package com.example.amaltea.model.evaluation;

/**
 * The verdict on one run: one configuration on one document.
 * <p>
 * The three checks run in sequence F1 → F2 → F3 with early exit. If a blocking check has
 * already failed, the later ones are not run at all.
 * <p>
 * F3 comes in two forms. During the descent it is {@link NarrativeReport}: deterministic,
 * free, no threshold to agree on. The LLM judge ({@link JudgeScore}) is optional and runs
 * only at the end of the campaign, on the threshold configurations — the smallest model that
 * passed F1+F2 and the one just below. So on almost every run {@code judgeScore} is null and
 * {@code narrativeReport} is not.
 * <p>
 * F1 is blocking with no tolerance. F2 is blocking too, though whether it should admit a
 * tolerance is still open.
 *
 * @param hardFailReport  outcome of F1, or {@code null} if not reached
 * @param dataFailReport  outcome of F2, or {@code null} if F1 already failed
 * @param narrativeReport outcome of the deterministic F3, or {@code null} if a blocking
 *                        check already failed
 * @param judgeScore      outcome of the LLM judge; {@code null} on every run of the descent
 * @param hardFail        F1 verdict
 * @param dataFail        F2 verdict
 * @param softFail        F3 verdict
 * @param passed          the overall verdict for this run
 */
public record EvaluationVerdict(
        HardFailReport hardFailReport,
        DataFailReport dataFailReport,
        NarrativeReport narrativeReport,
        JudgeScore judgeScore,
        boolean hardFail,
        boolean dataFail,
        boolean softFail,
        boolean passed
) {
    /** A run stopped at F1: the two later checks were never run. */
    public static EvaluationVerdict hardFailed(HardFailReport report) {
        return new EvaluationVerdict(report, null, null, null, true, false, false, false);
    }

    /** A run stopped at F2: F3 was never reached. */
    public static EvaluationVerdict dataFailed(HardFailReport hard, DataFailReport data) {
        return new EvaluationVerdict(hard, data, null, null, false, true, false, false);
    }

    /** Whether the LLM judge was run on this verdict at all. */
    public boolean judged() {
        return judgeScore != null;
    }
}
