package com.example.amaltea.model.blueprint;

import java.util.Map;

/**
 * How one CAPRA role should reach its model.
 *
 * @param provider       which client to build
 * @param baseUrl        endpoint; for local models, the VM address
 * @param apiKey         real key for the baseline, a placeholder for local endpoints
 * @param model          model identifier as the endpoint expects it
 * @param timeoutSeconds budget for a <em>single</em> HTTP request, not for the whole logical
 *                       call: with three application-level attempts the worst case is
 *                       {@code 3 × timeoutSeconds} plus the backoffs
 * @param options        model options passed through as-is (temperature, seed, max tokens…)
 */
public record RoleConfig(
        Provider provider,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        Map<String, Object> options
) {
    /**
     * Merges this configuration over a set of defaults, field by field.
     * <p>
     * Shallow merge: a role that overrides only {@code baseUrl} and {@code model} keeps the
     * defaults for everything else. This is what keeps blueprint files short — temperature,
     * seed and timeout are written once.
     *
     * @param defaults the blueprint-wide defaults
     * @return the effective configuration for the role
     */
    public RoleConfig mergedOver(RoleConfig defaults) {
        if (defaults == null) return this;
        return new RoleConfig(
                provider != null ? provider : defaults.provider(),
                baseUrl != null ? baseUrl : defaults.baseUrl(),
                apiKey != null ? apiKey : defaults.apiKey(),
                model != null ? model : defaults.model(),
                timeoutSeconds > 0 ? timeoutSeconds : defaults.timeoutSeconds(),
                options != null && !options.isEmpty() ? options : defaults.options()
        );
    }
}
