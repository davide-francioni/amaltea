package com.example.amaltea.model.catalog;

/**
 * A candidate model, normalised and ready to be ordered.
 * <p>
 * Two parameter counts rather than one, because a Mixture-of-Experts has consequences that
 * pull in opposite directions: <b>total</b> parameters must all be loaded into memory, so
 * they set the VRAM and the instance cost; <b>active</b> parameters do the work per token,
 * so they set latency and energy. A 47B/13B MoE is smaller than a 32B dense by compute but
 * larger by memory. For dense models the two values coincide.
 *
 * @param modelId             registry identifier, e.g. {@code Qwen/Qwen2.5-7B-Instruct}
 * @param name                display name
 * @param family              model family; the descent happens within one family so that
 *                            size and family are not confounded as variables
 * @param totalParameterCount all parameters — determines VRAM and instance cost
 * @param activeParameterCount parameters used per token — equals the total for dense models
 * @param parameterSource     how the count was obtained; see {@link ParameterSource}
 * @param mixtureOfExperts    whether the two counts differ
 * @param quantization        fixed for the whole campaign as an experimental condition,
 *                            not a variable of the research
 * @param minVramGb           VRAM needed to serve it; roughly 2 GB per billion at BF16
 */
public record SlmModel(
        String modelId,
        String name,
        String family,
        long totalParameterCount,
        long activeParameterCount,
        ParameterSource parameterSource,
        boolean mixtureOfExperts,
        String quantization,
        int minVramGb
) {}
