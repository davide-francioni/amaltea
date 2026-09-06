package com.example.amaltea.service;

import com.example.amaltea.model.evaluation.HardFailReport;
import com.example.amaltea.model.execution.CapraRunResult;
import org.springframework.stereotype.Service;

/**
 * Check F1: did the system reach the end at all?
 * <p>
 * The simplest of the three checks and the only one whose logic is fully settled — nothing
 * here depends on a threshold still to be agreed. Blocking with no tolerance: if any condition
 * triggers, the run does not pass.
 * <p>
 * The four conditions are recorded separately even though they lead to the same verdict,
 * because they say different things about <em>where</em> the system gave way. No {@code .tex}
 * means the LaTeX model produced nothing usable; a {@code .tex} without a {@code .pdf} means it
 * produced something syntactically broken. Two different ways of failing — and exactly the
 * distinction the supervisor's question B needs, since it points at which role gave up.
 */
@Service
public class HardFailChecker {

    /**
     * Applies the four blocking conditions.
     *
     * @param result what CAPRA returned for one document
     * @return the report; {@link HardFailReport#failed()} is the verdict
     */
    public HardFailReport check(CapraRunResult result) {
        boolean httpFailure = result.httpStatus() != 200;
        boolean texMissing = !result.texGenerated();
        boolean pdfMissing = !result.pdfGenerated();
        boolean timedOut = result.timedOut();

        return new HardFailReport(
                httpFailure,
                texMissing,
                pdfMissing,
                timedOut,
                describe(result, httpFailure, texMissing, pdfMissing, timedOut)
        );
    }

    /**
     * Diagnostic text. It explains, it does not decide.
     * <p>
     * Ordered from earliest to latest failure in the pipeline, so the first line names the
     * point where things actually stopped rather than a downstream consequence.
     */
    private String describe(CapraRunResult result,
                            boolean httpFailure,
                            boolean texMissing,
                            boolean pdfMissing,
                            boolean timedOut) {
        if (timedOut) {
            return "Nessuna risposta entro il budget della run.";
        }
        if (httpFailure) {
            return "La pipeline si è interrotta: HTTP " + result.httpStatus() + ".";
        }
        if (texMissing) {
            return "Il ruolo LATEX_REPORT non ha prodotto un sorgente utilizzabile.";
        }
        if (pdfMissing) {
            return "Sorgente LaTeX prodotto ma non compilabile: "
                    + (result.compileError() == null ? "causa non riportata" : result.compileError());
        }
        return "Nessuna condizione bloccante.";
    }
}
