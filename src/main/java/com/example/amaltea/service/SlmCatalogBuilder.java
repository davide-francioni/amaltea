package com.example.amaltea.service;

import com.example.amaltea.model.catalog.CatalogFilterCriteria;
import com.example.amaltea.model.catalog.ParameterSource;
import com.example.amaltea.model.catalog.RegistryModelEntry;
import com.example.amaltea.model.catalog.SlmCatalog;
import com.example.amaltea.model.catalog.SlmModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw registry data into an ordered catalogue of candidates.
 * <p>
 * This is the fragile part of the discovery mechanism, and it is deliberately confined to
 * one class: registries do not expose parameter counts uniformly, and families are not a
 * concept they represent at all. Both have to be derived, and derivation that is scattered
 * becomes an implicit heuristic nobody can document.
 */
@Service
public class SlmCatalogBuilder {

    private static final Logger log = LoggerFactory.getLogger(SlmCatalogBuilder.class);

    /** Bytes per parameter at the precision the campaign uses. */
    private static final int BYTES_PER_PARAM = 2;

    /** Headroom over the weights, for the intermediate-value cache and the engine. */
    private static final double VRAM_OVERHEAD = 1.4;

    private static final Pattern SIZE_IN_NAME =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[bB](?![a-zA-Z])");

    private final ModelRegistryClient registry;

    public SlmCatalogBuilder(ModelRegistryClient registry) {
        this.registry = registry;
    }

    /**
     * Discovers, normalises and filters the candidates, then freezes the result.
     * <p>
     * The snapshot is what makes a comparison between campaigns interpretable: discovery
     * stays current, but the set actually explored on a given occasion is recorded.
     * Without it, a threshold found today and one found next year could differ because of
     * the factor under study or because of a catalogue that shifted in the meantime, and
     * nothing would tell the two apart.
     */
    public SlmCatalog build(CatalogFilterCriteria criteria) {
        List<SlmModel> models = new ArrayList<>();
        int discarded = 0;

        for (RegistryModelEntry entry : registry.search(criteria)) {
            if (!passesFilters(entry, criteria)) {
                continue;
            }
            SlmModel model = normalise(entry, criteria);
            if (model == null) {
                discarded++;
                continue;
            }
            if (criteria.excludeMixtureOfExperts() && model.mixtureOfExperts()) {
                continue;
            }
            if (criteria.maxVramGb() > 0 && model.minVramGb() > criteria.maxVramGb()) {
                continue;
            }
            models.add(model);
        }

        SlmCatalog catalog = new SlmCatalog(models, LocalDateTime.now(), criteria);
        log.info("Catalogo costruito: {} candidati, {} scartati per conteggio irrisolvibile. {}",
                models.size(), discarded, catalog.coverageSummary());
        return catalog;
    }

    // ------------------------------------------------------------------ normalisation

    private SlmModel normalise(RegistryModelEntry entry, CatalogFilterCriteria criteria) {
        Map<String, Object> config = registry.fetchConfig(entry.modelId());

        Resolved resolved = resolveParameterCount(entry, config);
        if (resolved == null) {
            return null;
        }

        long active = activeParameterCount(config, resolved.total());
        boolean moe = active > 0 && active < resolved.total();

        return new SlmModel(
                entry.modelId(),
                shortName(entry.modelId()),
                deriveFamily(entry),
                resolved.total(),
                moe ? active : resolved.total(),
                resolved.source(),
                moe,
                "BF16",
                estimateVram(resolved.total()));
    }

    /**
     * The resolution cascade, tried in decreasing order of authority.
     * <p>
     * What matters is not the cascade itself but that the level which produced the value
     * is recorded: a count computed from the architecture and one parsed from a name do
     * not carry the same evidential weight, and the ordering of the search depends on
     * precisely that number. With the provenance kept, the thesis can state the quality
     * of the data instead of presenting it as uniformly certain.
     */
    private Resolved resolveParameterCount(RegistryModelEntry entry, Map<String, Object> config) {
        long fromHeader = sumTensorShapes(registry.readTensorHeader(entry.modelId()));
        if (fromHeader > 0) {
            return new Resolved(fromHeader, ParameterSource.SAFETENSORS_METADATA);
        }

        long computed = computeFromConfig(config);
        if (computed > 0) {
            return new Resolved(computed, ParameterSource.CONFIG_JSON_COMPUTED);
        }

        long fromName = parseFromName(entry.modelId());
        if (fromName > 0) {
            // Last resort, and wrong on mixture-of-experts models: the name reports the
            // total for some families and the active count for others.
            return new Resolved(fromName, ParameterSource.NAME_PARSING);
        }

        log.debug("Conteggio parametri irrisolvibile per {}", entry.modelId());
        return null;
    }

    /** Sums the products of the tensor shapes declared in the weights header. */
    @SuppressWarnings("unchecked")
    private long sumTensorShapes(Map<String, Object> header) {
        long total = 0;
        for (Map.Entry<String, Object> entry : header.entrySet()) {
            if ("__metadata__".equals(entry.getKey()) || !(entry.getValue() instanceof Map<?, ?> tensor)) {
                continue;
            }
            Object shape = tensor.get("shape");
            if (shape instanceof List<?> dimensions && !dimensions.isEmpty()) {
                long product = 1;
                for (Object dimension : dimensions) {
                    if (dimension instanceof Number n) {
                        product *= n.longValue();
                    }
                }
                total += product;
            }
        }
        return total;
    }

    /**
     * Approximates the parameter count from the architectural hyperparameters.
     * <p>
     * Deliberately coarse: it exists to place a model on the ladder, not to report an
     * exact figure. Its real value is that it is the only level of the cascade that
     * handles mixture-of-experts correctly, because it can see how many experts there are.
     */
    private long computeFromConfig(Map<String, Object> config) {
        long hidden = longValue(config, "hidden_size");
        long layers = longValue(config, "num_hidden_layers");
        long vocab = longValue(config, "vocab_size");
        long intermediate = longValue(config, "intermediate_size");
        if (hidden == 0 || layers == 0) {
            return 0;
        }
        long experts = Math.max(1, longValue(config, "num_local_experts"));
        long attention = 4L * hidden * hidden;
        long feedForward = 3L * hidden * (intermediate > 0 ? intermediate : hidden * 4) * experts;
        return layers * (attention + feedForward) + 2L * vocab * hidden;
    }

    private long activeParameterCount(Map<String, Object> config, long total) {
        long experts = longValue(config, "num_local_experts");
        long perToken = longValue(config, "num_experts_per_tok");
        if (experts <= 1 || perToken <= 0) {
            return total;
        }
        // Attention is shared and always active; only the expert blocks are sparse.
        return Math.round(total * (0.35 + 0.65 * ((double) perToken / experts)));
    }

    private long parseFromName(String modelId) {
        Matcher matcher = SIZE_IN_NAME.matcher(modelId);
        long largest = 0;
        while (matcher.find()) {
            largest = Math.max(largest,
                    Math.round(Double.parseDouble(matcher.group(1)) * 1_000_000_000L));
        }
        return largest;
    }

    // ------------------------------------------------------------------ families

    /**
     * Derives the family from owner and repository name, stripping the size token.
     * <p>
     * The registry has no notion of a family, so this is a heuristic over strings — and
     * the entire structure of the search rests on it. Getting the grouping wrong would
     * build a ladder mixing models that are not comparable, with nothing to signal it.
     * <p>
     * The mitigation is procedural rather than technical: an open discovery pass produces
     * the candidate grouping, a human confirms it, and the campaign then runs with the
     * families named explicitly in the criteria. That review is an intervention and has to
     * be declared as such.
     */
    private String deriveFamily(RegistryModelEntry entry) {
        String id = entry.modelId();
        int slash = id.indexOf('/');
        String owner = slash > 0 ? id.substring(0, slash) : "";
        String name = slash > 0 ? id.substring(slash + 1) : id;

        String stripped = SIZE_IN_NAME.matcher(name).replaceAll("");
        stripped = stripped.replaceAll("(?i)[-_]?(instruct|chat|it|base)\\b", "");
        stripped = stripped.replaceAll("[-_]{2,}", "-").replaceAll("^[-_]+|[-_]+$", "");

        return owner.isEmpty() ? stripped : owner + "/" + stripped;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Memory needed to serve the model.
     * <p>
     * Weights plus headroom. The headroom is not negligible here: the corpus runs to
     * seventy pages per document, so contexts are long and the intermediate-value cache
     * grows accordingly.
     */
    private int estimateVram(long parameterCount) {
        double weightsGb = (double) parameterCount * BYTES_PER_PARAM / 1_000_000_000.0;
        return (int) Math.ceil(weightsGb * VRAM_OVERHEAD);
    }

    private boolean passesFilters(RegistryModelEntry entry, CatalogFilterCriteria criteria) {
        if (criteria.minDownloads() > 0 && entry.downloads() < criteria.minDownloads()) {
            return false;
        }
        if (criteria.requiredFormats() != null && !criteria.requiredFormats().isEmpty()
                && entry.availableFormats().stream().noneMatch(criteria.requiredFormats()::contains)) {
            return false;
        }
        if (criteria.allowedLicenses() != null && !criteria.allowedLicenses().isEmpty()
                && !criteria.allowedLicenses().contains(entry.license())) {
            return false;
        }
        if (criteria.instructTunedOnly() && !entry.modelId().toLowerCase().matches(".*(instruct|chat|-it).*")) {
            return false;
        }
        if (criteria.isFamilyRestricted()
                && criteria.allowedFamilies().stream().noneMatch(f -> entry.modelId().startsWith(f))) {
            return false;
        }
        return true;
    }

    private long longValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof Number n ? n.longValue() : 0;
    }

    private String shortName(String modelId) {
        int slash = modelId.indexOf('/');
        return slash > 0 ? modelId.substring(slash + 1) : modelId;
    }

    /** A parameter count together with the level of the cascade that produced it. */
    private record Resolved(long total, ParameterSource source) {}
}
