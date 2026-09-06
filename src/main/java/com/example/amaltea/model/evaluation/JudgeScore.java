package com.example.amaltea.model.evaluation;

/**
 * Outcome of check F3: the judge's scores on the narrative text.
 * <p>
 * The data are read but the model returns them without the score being combined here: how
 * {@code recall}, {@code faithfulness} and {@code coherence} weigh into {@code overall}, and
 * which threshold separates pass from fail, are still to be agreed with the supervisor.
 * Until then the scores are collected without being used as a cut-off, so the threshold can
 * be calibrated afterwards on real results rather than guessed in advance.
 *
 * @param recall       how much of what CAPRA found the SLM also found
 * @param faithfulness whether the claims are supported by the document, or invented
 * @param coherence    whether the text holds together and says the same thing as the data
 * @param overall      aggregate; the combination rule is not fixed yet
 */
public record JudgeScore(
        double recall,
        double faithfulness,
        double coherence,
        double overall
) {}
