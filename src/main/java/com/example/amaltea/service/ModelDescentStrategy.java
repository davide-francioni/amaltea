package com.example.amaltea.service;

import com.example.amaltea.model.LlmRole;
import com.example.amaltea.model.catalog.SlmCatalog;
import com.example.amaltea.model.catalog.SlmModel;
import com.example.amaltea.model.process.ConfigurationTrial;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which configuration to try next, or when to stop.
 * <p>
 * Stateless: the search state is derived from the trial history each time, so nothing has to be
 * kept in sync and a process can be resumed from its archive.
 *
 * <h2>Structure of the search</h2>
 * The descent runs <b>independently inside each admissible family</b>: within a family the
 * ladder is clean and monotonic, so size is the only variable. Across families the results are
 * compared on VRAM, which is objective between vendors. How many families take part is not
 * chosen here — it is an outcome of the catalogue's filter criteria.
 *
 * <h2>Binary, plus a confirmation</h2>
 * With seven sizes a linear walk costs up to seven trials, a binary one about three. Each trial
 * is ten CAPRA runs plus provisioning, so hours. But binary search is only valid if
 * monotonicity holds, and that assumption is <em>not</em> validated: hence one extra trial
 * below the threshold, which puts the assumption to the test instead of taking it for granted.
 *
 * <h2>Per-role intervals</h2>
 * A uniform failure says CAPRA as a whole gave way at that size, not <em>which role</em> did —
 * and the best answer may well be heterogeneous. So each role carries its own interval, with
 * the constraint that a role never goes back up to a model that already worked for it.
 */
@Service
public class ModelDescentStrategy {

    /**
     * The next candidate for the uniform descent within one family.
     * <p>
     * The ladder is largest-first. {@code lo} is the deepest index known to pass, {@code hi} the
     * shallowest known to fail; the candidate is the midpoint between them. When they become
     * adjacent the boundary is found and the search moves to confirmation.
     *
     * @param catalog the frozen snapshot
     * @param family  which ladder to walk down
     * @param history trials already run, in any order
     * @return the model to try, or empty if this family's boundary is settled
     */
    public Optional<SlmModel> nextUniformCandidate(SlmCatalog catalog,
                                                   String family,
                                                   List<ConfigurationTrial> history) {
        List<SlmModel> ladder = catalog.ladderFor(family);
        if (ladder.isEmpty()) {
            return Optional.empty();
        }
        Interval bounds = uniformBounds(ladder, history);

        if (bounds.hi() - bounds.lo() <= 1) {
            return Optional.empty();
        }
        return Optional.of(ladder.get((bounds.lo() + bounds.hi()) / 2));
    }

    /**
     * The model one step below the established threshold, to test monotonicity.
     * <p>
     * Binary search assumes that once a size fails, every smaller size fails too. If this
     * candidate <em>passes</em>, the assumption is broken — and that fact gets recorded rather
     * than staying invisible, which is the whole point of spending the extra trial.
     *
     * @return the model to confirm against, or empty if there is nothing below
     */
    public Optional<SlmModel> confirmationCandidate(SlmCatalog catalog,
                                                    String family,
                                                    List<ConfigurationTrial> history) {
        List<SlmModel> ladder = catalog.ladderFor(family);
        Interval bounds = uniformBounds(ladder, history);

        if (bounds.hi() - bounds.lo() > 1) {
            return Optional.empty();
        }
        int below = bounds.hi() + 1;
        if (below >= ladder.size()) {
            return Optional.empty();
        }
        // The confirmation is a single trial, and the bounds do not record that it
        // happened: `hi` tracks the *shallowest* failure, so a deeper one leaves it where
        // it was. Without this check the same candidate would be proposed for ever, and
        // the caller would provision a machine on every iteration.
        if (alreadyTried(ladder.get(below), history)) {
            return Optional.empty();
        }
        return Optional.of(ladder.get(below));
    }

    /** Whether a model has already been evaluated, in any assignment. */
    private boolean alreadyTried(SlmModel model, List<ConfigurationTrial> history) {
        return history.stream()
                .filter(trial -> trial.verdict() != null)
                .anyMatch(trial -> trial.modelAssignment().containsValue(model));
    }

    /**
     * The next assignment for the refinement phase: lower the roles that are not to blame,
     * leaving the bottleneck where it is.
     * <p>
     * Six binary searches, coupled only by the fact that CAPRA runs as a whole — so every trial
     * narrows several intervals at once, which is what makes them affordable.
     *
     * @return the assignment to try, or empty when no interval can narrow any further
     */
    public Optional<Map<LlmRole, SlmModel>> nextRefinement(SlmCatalog catalog,
                                                           String family,
                                                           List<ConfigurationTrial> history) {
        List<SlmModel> ladder = catalog.ladderFor(family);
        if (ladder.isEmpty()) {
            return Optional.empty();
        }

        Map<LlmRole, Interval> perRole = roleBounds(ladder, history);
        Map<LlmRole, SlmModel> assignment = new EnumMap<>(LlmRole.class);
        boolean anyNarrowing = false;

        for (LlmRole role : LlmRole.values()) {
            Interval bounds = perRole.get(role);
            if (bounds.hi() - bounds.lo() > 1) {
                assignment.put(role, ladder.get((bounds.lo() + bounds.hi()) / 2));
                anyNarrowing = true;
            } else {
                // Settled: keep this role at its smallest known working model.
                int settled = Math.max(bounds.lo(), 0);
                assignment.put(role, ladder.get(settled));
            }
        }
        return anyNarrowing ? Optional.of(assignment) : Optional.empty();
    }

    /**
     * Whether this family has nothing left to try.
     *
     * @return {@code true} when both the uniform boundary and every role interval are settled
     */
    public boolean shouldStop(SlmCatalog catalog, String family, List<ConfigurationTrial> history) {
        return nextUniformCandidate(catalog, family, history).isEmpty()
                && confirmationCandidate(catalog, family, history).isEmpty()
                && nextRefinement(catalog, family, history).isEmpty();
    }

    /**
     * The smallest model that passed for a role — the answer this search produces.
     *
     * @return the threshold model, or empty if nothing passed (a valid, if negative, result)
     */
    public Optional<SlmModel> thresholdFor(SlmCatalog catalog,
                                           String family,
                                           List<ConfigurationTrial> history,
                                           LlmRole role) {
        List<SlmModel> ladder = catalog.ladderFor(family);
        int lo = roleBounds(ladder, history).get(role).lo();
        return lo >= 0 ? Optional.of(ladder.get(lo)) : Optional.empty();
    }

    /**
     * Whether monotonicity was violated: some model passed below one that failed.
     * <p>
     * Not an error to correct — a finding to report. It would mean the binary search rests on a
     * false premise, and that the thesis has to say so.
     */
    public boolean monotonicityViolated(SlmCatalog catalog,
                                        String family,
                                        List<ConfigurationTrial> history) {
        List<SlmModel> ladder = catalog.ladderFor(family);
        int deepestFailing = -1;
        for (ConfigurationTrial trial : passOrFail(history, false)) {
            deepestFailing = Math.max(deepestFailing, indexOf(ladder, trial));
        }
        for (ConfigurationTrial trial : passOrFail(history, true)) {
            if (indexOf(ladder, trial) > deepestFailing && deepestFailing >= 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- internals

    /** Bounds of the uniform search: deepest passing index, shallowest failing index. */
    private Interval uniformBounds(List<SlmModel> ladder, List<ConfigurationTrial> history) {
        int lo = -1;
        int hi = ladder.size();
        for (ConfigurationTrial trial : history) {
            if (!isUniform(trial) || trial.verdict() == null) {
                continue;
            }
            int index = indexOf(ladder, trial);
            if (index < 0) {
                continue;
            }
            if (trial.verdict().passed()) {
                lo = Math.max(lo, index);
            } else {
                hi = Math.min(hi, index);
            }
        }
        return new Interval(lo, hi);
    }

    /**
     * Per-role bounds.
     * <p>
     * The non-regression constraint lives here: {@code lo} only ever moves deeper into the
     * ladder, so a candidate for a role is always strictly smaller than the smallest model that
     * already worked for it.
     */
    private Map<LlmRole, Interval> roleBounds(List<SlmModel> ladder,
                                              List<ConfigurationTrial> history) {
        Map<LlmRole, Interval> bounds = new EnumMap<>(LlmRole.class);
        for (LlmRole role : LlmRole.values()) {
            bounds.put(role, new Interval(-1, ladder.size()));
        }
        for (ConfigurationTrial trial : history) {
            if (trial.verdict() == null) {
                continue;
            }
            boolean passed = trial.verdict().passed();
            trial.modelAssignment().forEach((role, model) -> {
                int index = ladder.indexOf(model);
                if (index < 0) {
                    return;
                }
                Interval current = bounds.get(role);
                bounds.put(role, passed
                        ? new Interval(Math.max(current.lo(), index), current.hi())
                        : new Interval(current.lo(), Math.min(current.hi(), index)));
            });
        }
        return bounds;
    }

    /** A trial is uniform when every role carries the same model. */
    private boolean isUniform(ConfigurationTrial trial) {
        return trial.modelAssignment().values().stream().distinct().count() == 1;
    }

    private int indexOf(List<SlmModel> ladder, ConfigurationTrial trial) {
        return trial.modelAssignment().values().stream()
                .findFirst()
                .map(ladder::indexOf)
                .orElse(-1);
    }

    private List<ConfigurationTrial> passOrFail(List<ConfigurationTrial> history, boolean passed) {
        return history.stream()
                .filter(t -> t.verdict() != null && t.verdict().passed() == passed)
                .filter(this::isUniform)
                .toList();
    }

    /**
     * Search bounds over a ladder, largest-first.
     *
     * @param lo deepest index known to pass, or -1 if none does
     * @param hi shallowest index known to fail, or {@code ladder.size()} if none does
     */
    private record Interval(int lo, int hi) {}
}
