package com.example.amaltea.model.blueprint;

import com.example.amaltea.model.InterventionLevel;
import com.example.amaltea.model.LlmRole;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A lightweight adapter (LoRA / QLoRA) trained for one role.
 * <p>
 * Only used at {@link InterventionLevel#L3_FINE_TUNING}. CAPRA's standard outputs are
 * already labelled data — input PDF, expected JSON — so no manual annotation is needed.
 * <p>
 * {@code trainingDocumentIds} is not bookkeeping: it is the documentary proof that training
 * and evaluation did not overlap. Without it the L3 result would not be defensible, because
 * training and evaluating on the same ten documents would invalidate it.
 *
 * @param id                  adapter identifier
 * @param role                the role this adapter was trained for
 * @param baseModelId         the model it was trained on top of
 * @param trainingDocumentIds documents used for training, therefore excluded from evaluation
 * @param trainedAt           when it was produced
 */
public record FineTuneAdapter(
        String id,
        LlmRole role,
        String baseModelId,
        List<String> trainingDocumentIds,
        LocalDateTime trainedAt
) {}
