package com.example.amaltea.service;

import com.example.amaltea.model.evaluation.DataFailReport;
import com.example.amaltea.model.evaluation.EvaluationVerdict;
import com.example.amaltea.model.evaluation.HardFailReport;
import com.example.amaltea.model.evaluation.NarrativeReport;
import com.example.amaltea.model.execution.CapraRunResult;
import com.example.amaltea.model.process.CapraStandardOutput;
import com.example.amaltea.model.process.ReferenceVariance;
import org.springframework.stereotype.Service;

/**
 * Applies the three checks in sequence and produces the verdict for one run.
 * <p>
 * The order is F1 → F2 → F3, with an early exit at the first blocking failure. It is not
 * only an optimisation: the order reflects the structure of the system under test — F1
 * concerns the pipeline as a whole, F2 the content the analysis agents produced, F3 its
 * textual rendering. A failure upstream makes the downstream checks uninterpretable, so
 * running them would add noise rather than information.
 * <p>
 * The LLM judge is deliberately absent from this class. It does not run during the
 * descent: it is invoked at the end of a campaign, on the threshold configurations only,
 * and its threshold is calibrated on the scores actually observed rather than fixed in
 * advance. Wiring it in here would make an optional component look mandatory.
 */
@Service
public class EvaluationService {

    private final HardFailChecker hardFailChecker;
    private final DataFailChecker dataFailChecker;
    private final NarrativeConsistencyChecker narrativeChecker;

    public EvaluationService(HardFailChecker hardFailChecker,
                             DataFailChecker dataFailChecker,
                             NarrativeConsistencyChecker narrativeChecker) {
        this.hardFailChecker = hardFailChecker;
        this.dataFailChecker = dataFailChecker;
        this.narrativeChecker = narrativeChecker;
    }

    /**
     * Evaluates one run against its reference.
     *
     * @param variance the noise floor for that document; may be {@code null} if it has
     *                 not been measured, in which case every divergence counts
     */
    public EvaluationVerdict evaluate(CapraRunResult result,
                                      CapraStandardOutput standard,
                                      ReferenceVariance variance) {

        HardFailReport hard = hardFailChecker.check(result);
        if (hard.failed()) {
            return EvaluationVerdict.hardFailed(hard);
        }

        DataFailReport data = dataFailChecker.check(result, standard, variance);
        if (data.failed()) {
            return EvaluationVerdict.dataFailed(hard, data);
        }

        NarrativeReport narrative = narrativeChecker.check(result, standard);

        return new EvaluationVerdict(
                hard, data, narrative, null,
                false, false, narrative.failed(),
                !narrative.failed());
    }
}
