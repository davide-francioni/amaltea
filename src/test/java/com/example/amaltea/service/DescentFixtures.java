package com.example.amaltea.service;

import com.example.amaltea.model.LlmRole;
import com.example.amaltea.model.blueprint.Blueprint;
import com.example.amaltea.model.catalog.CatalogFilterCriteria;
import com.example.amaltea.model.catalog.ParameterSource;
import com.example.amaltea.model.catalog.SlmCatalog;
import com.example.amaltea.model.catalog.SlmModel;
import com.example.amaltea.model.process.ConfigurationTrial;
import com.example.amaltea.model.process.TrialVerdict;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds synthetic catalogues and trial histories for the tests.
 * <p>
 * The search strategy is the one piece of non-trivial logic in AMALTEA, and it is also the
 * one whose failure would be silent: a mistake in the interval arithmetic would return a
 * wrong threshold without raising anything, the campaign would run, produce numbers, and
 * those numbers would end up in the thesis. Testing it needs no infrastructure at all —
 * only a ladder of models and a record of what passed — so there is no reason not to.
 */
final class DescentFixtures {

    private DescentFixtures() {
    }

    /** A dense family whose sizes descend by halving, largest first. */
    static SlmCatalog ladder(String family, long... billions) {
        List<SlmModel> models = new ArrayList<>();
        for (long b : billions) {
            models.add(dense(family, b));
        }
        return new SlmCatalog(models, LocalDateTime.now(), criteria(4));
    }

    static SlmModel dense(String family, long billions) {
        long count = billions * 1_000_000_000L;
        return new SlmModel(
                family + "/model-" + billions + "b",
                "model-" + billions + "b",
                family,
                count, count,
                ParameterSource.SAFETENSORS_METADATA,
                false,
                "BF16",
                (int) (billions * 2));
    }

    static SlmModel mixtureOfExperts(String family, long totalBillions, long activeBillions) {
        return new SlmModel(
                family + "/moe-" + totalBillions + "b",
                "moe-" + totalBillions + "b",
                family,
                totalBillions * 1_000_000_000L,
                activeBillions * 1_000_000_000L,
                ParameterSource.CONFIG_JSON_COMPUTED,
                true,
                "BF16",
                (int) (totalBillions * 2));
    }

    static CatalogFilterCriteria criteria(int minSizesPerFamily) {
        return new CatalogFilterCriteria(
                List.of(), minSizesPerFamily, true, List.of("apache-2.0"),
                List.of("safetensors"), 0, 0, true);
    }

    /** A completed trial with every role on the same model, and the given outcome. */
    static ConfigurationTrial uniformTrial(SlmModel model, boolean passed) {
        Map<LlmRole, SlmModel> assignment = new EnumMap<>(LlmRole.class);
        for (LlmRole role : LlmRole.values()) {
            assignment.put(role, model);
        }
        return trial(assignment, passed);
    }

    /** A completed trial with a mixed assignment. */
    static ConfigurationTrial mixedTrial(Map<LlmRole, SlmModel> assignment, boolean passed) {
        return trial(assignment, passed);
    }

    private static ConfigurationTrial trial(Map<LlmRole, SlmModel> assignment, boolean passed) {
        ConfigurationTrial trial = new ConfigurationTrial(
                "trial-" + assignment.hashCode() + "-" + passed, assignment, (Blueprint) null);
        trial.complete(new TrialVerdict(passed ? 10 : 0, passed ? 0 : 10, passed, false, null));
        return trial;
    }

    /** A trial still running: it must be ignored, because it carries no verdict yet. */
    static ConfigurationTrial pendingTrial(SlmModel model) {
        Map<LlmRole, SlmModel> assignment = new EnumMap<>(LlmRole.class);
        for (LlmRole role : LlmRole.values()) {
            assignment.put(role, model);
        }
        return new ConfigurationTrial("trial-pending", assignment, (Blueprint) null);
    }

    /** Convenience: an assignment with one role lowered below the rest. */
    static Map<LlmRole, SlmModel> assignmentWith(SlmModel base, LlmRole role, SlmModel other) {
        Map<LlmRole, SlmModel> assignment = new EnumMap<>(LlmRole.class);
        for (LlmRole r : LlmRole.values()) {
            assignment.put(r, r == role ? other : base);
        }
        return assignment;
    }
}
