package com.example.amaltea.model.catalog;

import java.util.List;

/**
 * The filters applied when discovering candidate models.
 * <p>
 * AMALTEA builds its own catalogue by querying a model registry: the list is not supplied by
 * hand. Without hard filters the search space is unbounded, so these criteria are what make
 * the descent meaningful.
 * <p>
 * <b>How many families end up in a campaign is an outcome of these criteria, not a
 * parameter.</b> The descent iterates over every admissible family; the thesis reports the
 * number as a finding — «criteria X selected N families, of which M had a usable ladder» —
 * rather than defending it as a choice.
 *
 * @param allowedFamilies         empty means «every family that passes the other filters».
 *                                Naming families here restricts the campaign explicitly, which
 *                                is a different thing from the ladder filter below
 * @param minSizesPerFamily       a family with fewer sizes than this is not an axis to descend
 *                                along — it is an isolated point, and is dropped from the
 *                                descent (it may still serve as a single comparison). Four is
 *                                the working value
 * @param instructTunedOnly       base models cannot follow CAPRA's prompts
 * @param allowedLicenses         licences compatible with the use
 * @param requiredFormats         {@code ["safetensors"]} for this campaign: the weights are
 *                                BF16, unquantized. Not only a quality choice — safetensors
 *                                repositories expose the metadata that makes the first and most
 *                                authoritative step of the parameter-count cascade work
 * @param minDownloads            crude proxy for reliability, filters abandoned uploads
 * @param maxVramGb               ceiling set by the GPU type fixed in the Terraform modules; at
 *                                BF16, roughly 2 GB per billion parameters
 * @param excludeMixtureOfExperts MoE trades memory for speed, while this research optimises for
 *                                memory. They are kept out of the main ladder and tried as a
 *                                targeted experiment on the threshold instead — see the safety
 *                                rule: if the admissible catalogue turned out to be dominated by
 *                                MoE, separate ladders per architecture would be needed
 */
public record CatalogFilterCriteria(
        List<String> allowedFamilies,
        int minSizesPerFamily,
        boolean instructTunedOnly,
        List<String> allowedLicenses,
        List<String> requiredFormats,
        long minDownloads,
        int maxVramGb,
        boolean excludeMixtureOfExperts
) {
    /** Whether the campaign was restricted to named families rather than left open. */
    public boolean isFamilyRestricted() {
        return allowedFamilies != null && !allowedFamilies.isEmpty();
    }
}
