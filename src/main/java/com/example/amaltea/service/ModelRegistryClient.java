package com.example.amaltea.service;

import com.example.amaltea.model.catalog.CatalogFilterCriteria;
import com.example.amaltea.model.catalog.RegistryModelEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the model registry. Transport only.
 * <p>
 * This class knows nothing about parameters, families or memory: that is the catalogue
 * builder's business. The separation is not formal — normalisation is the fragile part of
 * the discovery mechanism, and keeping it in one place is what makes it possible to
 * document it as a deliberate choice rather than leave it scattered as implicit heuristics.
 */
@Service
public class ModelRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryClient.class);

    private static final String HUB = "https://huggingface.co";
    private static final String TEXT_GENERATION = "text-generation";

    /**
     * How many candidates to pull from the listing call.
     * <p>
     * The registry holds over a million repositories, so fetching details for all of them
     * is out of the question. The listing is filtered and sorted server-side and only a
     * shortlist is examined in detail — two or three orders of magnitude fewer calls,
     * which is what makes discovery a matter of minutes.
     */
    private static final int SHORTLIST_SIZE = 500;

    private final RestClient client;
    private final ObjectMapper mapper = ReferenceArchive.archiveMapper();

    public ModelRegistryClient(RestClient registryRestClient) {
        this.client = registryRestClient;
    }

    /**
     * First pass: a filtered listing, ordered by popularity.
     * <p>
     * Popularity is a crude proxy, but it does one useful thing — it pushes abandoned and
     * experimental uploads below the cut, and those are the ones whose metadata is most
     * often incomplete.
     */
    public List<RegistryModelEntry> search(CatalogFilterCriteria criteria) {
        String body = client.get()
                .uri(HUB + "/api/models?filter={filter}&sort=downloads&direction=-1&limit={limit}&full=true",
                        TEXT_GENERATION, SHORTLIST_SIZE)
                .retrieve()
                .body(String.class);

        List<RegistryModelEntry> entries = new ArrayList<>();
        try {
            for (JsonNode node : mapper.readTree(body)) {
                entries.add(toEntry(node));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Risposta del registry non interpretabile", e);
        }
        log.info("Registry: {} candidati dalla lista iniziale", entries.size());
        return entries;
    }

    /** Second pass: full metadata, requested only for the shortlisted candidates. */
    public RegistryModelEntry fetchMetadata(String modelId) {
        String body = client.get()
                .uri(HUB + "/api/models/{id}", modelId)
                .retrieve()
                .body(String.class);
        try {
            return toEntry(mapper.readTree(body));
        } catch (Exception e) {
            throw new IllegalStateException("Metadati non interpretabili per " + modelId, e);
        }
    }

    /**
     * The architectural configuration, from which the parameter count can be computed.
     * <p>
     * Requested separately because the listing call does not carry it: configurations are
     * excluded from the full listing on account of their size.
     * <p>
     * This is the only source that distinguishes total from active parameters — the
     * weights header gives the total, but only the configuration says how many experts
     * are engaged per token.
     */
    public Map<String, Object> fetchConfig(String modelId) {
        try {
            String body = client.get()
                    .uri(HUB + "/{id}/resolve/main/config.json", modelId)
                    .retrieve()
                    .body(String.class);
            return mapper.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Configurazione non disponibile per {}: {}", modelId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Reads the weights header without downloading the weights.
     * <p>
     * The format is simple enough that its header — the tensor list with types and shapes
     * — can be retrieved with a small ranged HTTP request. This reverses the expected
     * order of the resolution cascade: the most authoritative source is not the most
     * expensive to try, but a few kilobytes to fetch first.
     * <p>
     * The layout is: eight bytes giving the header length, then that many bytes of JSON.
     *
     * @return the header, or an empty map if the model publishes no such file
     */
    public Map<String, Object> readTensorHeader(String modelId) {
        try {
            byte[] prefix = client.get()
                    .uri(HUB + "/{id}/resolve/main/model.safetensors", modelId)
                    .header("Range", "bytes=0-7")
                    .retrieve()
                    .body(byte[].class);

            if (prefix == null || prefix.length < 8) {
                return Map.of();
            }
            long headerLength = 0;
            for (int i = 7; i >= 0; i--) {
                headerLength = (headerLength << 8) | (prefix[i] & 0xFFL);
            }
            if (headerLength <= 0 || headerLength > 32 * 1024 * 1024) {
                return Map.of();
            }

            byte[] header = client.get()
                    .uri(HUB + "/{id}/resolve/main/model.safetensors", modelId)
                    .header("Range", "bytes=8-" + (7 + headerLength))
                    .retrieve()
                    .body(byte[].class);

            return header == null ? Map.of()
                    : mapper.readValue(header, new com.fasterxml.jackson.core.type.TypeReference<>() {});

        } catch (Exception e) {
            log.debug("Intestazione dei pesi non leggibile per {}: {}", modelId, e.getMessage());
            return Map.of();
        }
    }

    private RegistryModelEntry toEntry(JsonNode node) {
        Map<String, Object> raw = mapper.convertValue(node,
                new com.fasterxml.jackson.core.type.TypeReference<>() {});

        List<String> formats = new ArrayList<>();
        for (JsonNode sibling : node.path("siblings")) {
            String name = sibling.path("rfilename").asText("");
            if (name.endsWith(".safetensors")) {
                formats.add("safetensors");
            } else if (name.endsWith(".gguf")) {
                formats.add("gguf");
            }
        }

        return new RegistryModelEntry(
                node.path("id").asText(""),
                raw == null ? new HashMap<>() : raw,
                formats.stream().distinct().toList(),
                node.path("downloads").asLong(0),
                node.path("cardData").path("license").asText(""));
    }
}
