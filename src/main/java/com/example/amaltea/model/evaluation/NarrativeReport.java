package com.example.amaltea.model.evaluation;

import java.util.List;

/**
 * Outcome of check F3 in its deterministic form: does the narrative text agree with the
 * structured data?
 * <p>
 * This is what runs during the descent, in place of the LLM judge. It does not measure
 * semantic faithfulness in the full sense, but it catches the coarse failures — which are
 * also the likely ones with small models. No LLM call, no threshold to agree on, no judge
 * bias, and it costs nothing.
 * <p>
 * It exists because without it the {@code LATEX_REPORT} role would be measured by check F1
 * alone, that is by «it produced a .tex that compiles». A small model can emit syntactically
 * valid LaTeX that is semantically empty, or that contradicts the data it is supposed to
 * summarise, and pass.
 * <p>
 * Four lists rather than a score: with no LLM there is nothing to grade, there are concrete
 * findings — and listing them says <em>what</em> does not add up in the text, not just how
 * much.
 *
 * @param missingReferences     categories or issue identifiers present in the structured
 *                              report and never mentioned in the prose
 * @param numericContradictions figures in the text that contradict the data, e.g. «three
 *                              critical problems» when the JSON holds seven
 * @param sectionsMissing       narrative sections the reference has and this output does not
 * @param sectionsCollapsed     sections that exist but whose length fell far below the range
 *                              observed across repetitions of the reference
 * @param failed                the verdict
 */
public record NarrativeReport(
        List<String> missingReferences,
        List<String> numericContradictions,
        List<String> sectionsMissing,
        List<String> sectionsCollapsed,
        boolean failed
) {
    /** Nothing to report: the prose is consistent with the structured data. */
    public static NarrativeReport clean() {
        return new NarrativeReport(List.of(), List.of(), List.of(), List.of(), false);
    }
}
