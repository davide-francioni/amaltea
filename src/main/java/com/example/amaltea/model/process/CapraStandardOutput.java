package com.example.amaltea.model.process;

import java.time.LocalDateTime;

/**
 * What CAPRA produces on one document in its original configuration — the reference against
 * which every SLM run is compared.
 * <p>
 * Generated once per document and reused by every later process, which is why it is archived
 * separately from the processes themselves. Generated <b>repeatedly</b> for the same
 * document: with a single repetition, any divergence observed in an SLM run could be the
 * model's inability <em>or</em> plain run-to-run noise of the reference, and there would be
 * no way to tell.
 * <p>
 * It must be produced by the <b>refactored</b> CAPRA with the baseline blueprint, not by the
 * original one. Otherwise the comparison would be «original code + GPT-5.1» against
 * «refactored code + SLM», and a difference might come from the refactoring rather than from
 * the model.
 *
 * @param documentId          which document this is the reference for
 * @param repetitionIndex     1-based; several repetitions per document measure the noise floor
 * @param auditReportJson     the reference report
 * @param issueCount          how many issues it contains. Worth checking as soon as the
 *                            references exist: excellent reports yield few issues, and on a
 *                            thin reference a difference of one weighs enormously. That
 *                            number sets the discriminating power of the whole experiment
 * @param generatedAt         when it was produced
 * @param baselineBlueprintId which configuration produced it — without this it would not be
 *                            reproducible
 */
public record CapraStandardOutput(
        String documentId,
        int repetitionIndex,
        String auditReportJson,
        int issueCount,
        LocalDateTime generatedAt,
        String baselineBlueprintId
) {}
