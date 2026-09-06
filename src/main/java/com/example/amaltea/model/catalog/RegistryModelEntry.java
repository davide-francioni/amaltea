package com.example.amaltea.model.catalog;

import java.util.List;
import java.util.Map;

/**
 * Raw data as returned by the model registry, before normalisation.
 * <p>
 * Kept separate from {@link SlmModel} on purpose. Normalisation — extracting a parameter
 * count from incomplete metadata or from a name — is the fragile part of the discovery
 * mechanism, and keeping it in one explicit place is what makes it possible to document it
 * in the thesis as a deliberate choice rather than leaving it scattered as an implicit
 * heuristic.
 * <p>
 * Transient: consumed to produce an {@link SlmModel} and then discarded, never archived.
 *
 * @param modelId          registry identifier
 * @param rawMetadata      whatever the registry returned, untouched
 * @param availableFormats weight formats published for this model
 * @param downloads        popularity, usable as a crude reliability filter
 * @param license          as declared by the registry
 */
public record RegistryModelEntry(
        String modelId,
        Map<String, Object> rawMetadata,
        List<String> availableFormats,
        long downloads,
        String license
) {}
