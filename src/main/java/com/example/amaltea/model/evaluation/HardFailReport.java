package com.example.amaltea.model.evaluation;

/**
 * Outcome of check F1: did the system reach the end at all?
 * <p>
 * Blocking with no tolerance. Four flags rather than one boolean because the verdict is the
 * same in every case but the conditions say different things about <em>where</em> the system
 * gave way: no {@code .tex} means the LaTeX model produced nothing usable, while a
 * {@code .tex} without a {@code .pdf} means it produced something syntactically broken.
 * Two different ways of failing — and exactly the sort of distinction the supervisor's
 * question B needs.
 *
 * @param httpFailure the pipeline stopped before producing anything
 * @param texMissing  the LaTeX role produced no usable source
 * @param pdfMissing  the source exists but pdflatex will not compile it
 * @param timedOut    no answer within the budget set by AMALTEA
 * @param detail      diagnostic text; explains, does not decide
 */
public record HardFailReport(
        boolean httpFailure,
        boolean texMissing,
        boolean pdfMissing,
        boolean timedOut,
        String detail
) {
    /** True if any condition triggered. No tolerance. */
    public boolean failed() {
        return httpFailure || texMissing || pdfMissing || timedOut;
    }
}
