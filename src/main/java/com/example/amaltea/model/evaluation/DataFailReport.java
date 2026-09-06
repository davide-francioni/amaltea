package com.example.amaltea.model.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Outcome of check F2: did the run find the same things as the standard output?
 * <p>
 * <b>F2 never compares prose.</b> Descriptions, recommendations, feature evidence and
 * traceability references are free text produced by the model: an SLM will not use the same
 * words, so comparing them literally would fail on every run. F2 answers «did it find the
 * same things?», F3 answers «does the text describing them hold up?». Keeping the two apart
 * is what makes F2 deterministic and free of thresholds to agree on.
 * <p>
 * Three fields are excluded from the comparison for cause:
 * <ul>
 *   <li>{@code timestamp} — set to {@code now()} on every execution</li>
 *   <li>issue {@code id} — the ConsistencyManager renumbers positionally, so the id depends
 *       on the order findings arrive in, not on the identity of the problem. Two runs that
 *       find the same defects in a different order get different ids</li>
 *   <li>{@code confidenceScore} — a model's self-assessment. It says something about its
 *       calibration, not about whether it found the same thing</li>
 * </ul>
 *
 * <h2>What is compared, in decreasing order of reliability</h2>
 *
 * <b>1. Feature coverage.</b> The best signal. Aligned by {@code featureName}, which comes
 * from the MongoDB knowledge base and is therefore identical by construction across runs —
 * the model does not invent it. For each feature the {@code status} enum is compared
 * exactly. A vector of N aligned categorical labels: agreement is computable without any
 * fuzzy matching.
 * <p>
 * <b>2. Traceability matrix.</b> Aligned by {@code useCaseId}, extracted from the document.
 * Slightly less clean, because an SLM may skip a use case entirely: the identifier sets are
 * compared first, then the {@code hasDesign}/{@code hasTest} flags over the intersection.
 * <p>
 * <b>3. Issues.</b> On two planes. Aggregate — total, distribution by severity, count per
 * category — needs no matching at all. Set-level matching cannot use the id, so issues are
 * paired by {@code quote}: it is text extracted from the document and anchored by CAPRA's
 * evidence anchoring, so two runs finding the same problem quote the same passage. Compared
 * after normalisation, falling back to {@code (category, pageReference)} when the quote does
 * not match.
 *
 * <h2>Noise floor</h2>
 * Fields that varied between repetitions of the <em>reference itself</em> are absorbed and
 * recorded in {@code ignoredAsUnstable} rather than counted. If a feature's status flipped
 * between two runs of the same frontier model, a difference there is not evidence about
 * the SLM.
 *
 * @param featureMatches          features whose status matches the reference
 * @param featureTotal            features evaluated
 * @param traceabilityMatches     use cases whose design/test flags match
 * @param traceabilityTotal       use cases in the reference
 * @param traceabilityMissing     use case identifiers the reference has and this run lacks
 * @param issueCountDelta         difference in total issue count against the reference
 * @param issuesMissingBySeverity how many reference issues were not found, per severity.
 *                                Broken down because missing a CRITICAL is not the same as
 *                                missing a LOW — and because a graduated tolerance, if the
 *                                supervisor grants one, would have to be defined on this
 * @param issuesExtra             issues this run reports and the reference does not
 * @param missingFields           fields present in the reference and absent from the output
 * @param structuralDiffs         readable detail of the substantive differences
 * @param ignoredAsUnstable       divergences absorbed by the noise floor
 * @param withinTolerance         whether the divergence stays within the accepted threshold.
 *                                The tolerance is a reopened decision; recording the
 *                                measurements here means the threshold can be settled
 *                                afterwards without re-running the campaign
 * @param failed                  the verdict
 */
public record DataFailReport(
        int featureMatches,
        int featureTotal,
        int traceabilityMatches,
        int traceabilityTotal,
        List<String> traceabilityMissing,
        int issueCountDelta,
        Map<String, Integer> issuesMissingBySeverity,
        int issuesExtra,
        List<String> missingFields,
        List<String> structuralDiffs,
        List<String> ignoredAsUnstable,
        boolean withinTolerance,
        boolean failed
) {
    /**
     * Share of features whose status matches the reference, in [0,1].
     * <p>
     * The single cleanest F2 figure: aligned by a key the model does not control, compared
     * exactly, no fuzzy matching involved.
     */
    public double featureAgreement() {
        return featureTotal == 0 ? 1.0 : (double) featureMatches / featureTotal;
    }

    /** Share of use cases whose design/test flags match, in [0,1]. */
    public double traceabilityAgreement() {
        return traceabilityTotal == 0 ? 1.0 : (double) traceabilityMatches / traceabilityTotal;
    }

    /** How many reference issues were not found, across all severities. */
    public int totalIssuesMissing() {
        return issuesMissingBySeverity == null ? 0
                : issuesMissingBySeverity.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Whether any issue was missed at the given severity.
     * <p>
     * Exists to support a graduated tolerance — «zero on CRITICAL and HIGH, some on LOW» —
     * should the supervisor prefer that to a blanket rule.
     */
    public boolean missedAt(String severity) {
        return issuesMissingBySeverity != null
                && issuesMissingBySeverity.getOrDefault(severity, 0) > 0;
    }
}
