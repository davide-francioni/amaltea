package com.example.amaltea.model.catalog;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The candidate models for one benchmark process — a frozen snapshot.
 * <p>
 * AMALTEA discovers the catalogue by querying a registry, and that landscape changes: new
 * models appear, old ones disappear, metadata is updated. Re-running the same process six
 * months later would test a different set.
 * <p>
 * The snapshot does not limit what AMALTEA may discover. It is the record of what it found
 * <em>that time</em>, and it is precisely what makes the longitudinal comparison possible: if
 * the threshold later moves from 32B to 7B, that comparison can only be made by knowing what
 * the catalogue contained the first time.
 *
 * @param models     the candidates, already normalised and filtered
 * @param resolvedAt when the registry was queried
 * @param criteria   the filters that produced this list
 */
public record SlmCatalog(
        List<SlmModel> models,
        LocalDateTime resolvedAt,
        CatalogFilterCriteria criteria
) {
    /**
     * The families that can actually be descended, largest ladder first.
     * <p>
     * A family with fewer than {@code minSizesPerFamily} sizes is not an axis: it is an
     * isolated point. Dropping it from the descent is what keeps each ladder monotonic, so
     * that size and family never get mixed as variables inside a single search.
     * <p>
     * MoE models are excluded from the ladders when the criteria say so: they are tried as a
     * targeted experiment on the threshold instead (see {@link #mixtureOfExpertsNear}).
     *
     * @return family names, in decreasing order of ladder length
     */
    public List<String> descendableFamilies() {
        int minSizes = criteria == null ? 4 : criteria.minSizesPerFamily();
        Map<String, List<SlmModel>> byFamily = denseModels().stream()
                .collect(Collectors.groupingBy(SlmModel::family));
        return byFamily.entrySet().stream()
                .filter(e -> e.getValue().size() >= minSizes)
                .sorted(Comparator.comparingInt((Map.Entry<String, List<SlmModel>> e) ->
                        e.getValue().size()).reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The ladder of one family, largest first — the order of the descent.
     * <p>
     * Ordered by <em>total</em> parameter count: within a family it is unambiguous, and it is
     * also the figure that determines VRAM and instance cost.
     *
     * @param family which family to walk down
     * @return that family's dense models, largest first
     */
    public List<SlmModel> ladderFor(String family) {
        return denseModels().stream()
                .filter(m -> family.equals(m.family()))
                .sorted(Comparator.comparingLong(SlmModel::totalParameterCount).reversed())
                .toList();
    }

    /**
     * The MoE model closest in VRAM to a given threshold, for the targeted experiment that
     * follows the descent.
     * <p>
     * VRAM is the right axis here: it is always determined by the <em>total</em> parameter
     * count, so dense and MoE sit on one unambiguous ordering. What differs is their nature,
     * not their comparability — which is why they get their own experiment rather than a place
     * in the ladder.
     *
     * @param vramGb the VRAM of the threshold configuration
     * @return the nearest MoE candidate, or {@code null} if the catalogue holds none
     */
    public SlmModel mixtureOfExpertsNear(int vramGb) {
        return models.stream()
                .filter(SlmModel::mixtureOfExperts)
                .min(Comparator.comparingInt(m -> Math.abs(m.minVramGb() - vramGb)))
                .orElse(null);
    }

    /** Every dense candidate, whatever the family. */
    public List<SlmModel> denseModels() {
        return models.stream().filter(m -> !m.mixtureOfExperts()).toList();
    }

    /**
     * How many families were discovered against how many can be descended.
     * <p>
     * Worth reporting in the thesis: it turns «we picked N families» into «the criteria
     * selected N, of which M had a usable ladder».
     */
    public String coverageSummary() {
        long discovered = denseModels().stream().map(SlmModel::family).distinct().count();
        return "%d famiglie scoperte, %d percorribili".formatted(discovered, descendableFamilies().size());
    }
}
