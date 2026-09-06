package com.example.amaltea.model.blueprint;

import com.example.amaltea.model.InterventionLevel;
import com.example.amaltea.model.LlmRole;

import java.util.Map;

/**
 * The configuration of one CAPRA execution: which model answers for which role.
 * <p>
 * Written to YAML and handed to the CAPRA process at startup. One blueprint per execution,
 * one CAPRA process per blueprint — restarting costs a few seconds against runs that take
 * minutes, and buys complete state isolation between runs.
 * <p>
 * The unit of configuration is the <b>role</b>, not the provider and not the Java class:
 * that is what keeps blueprints valid even if CAPRA reorganises its code internally.
 *
 * @param id                unique identifier of the execution
 * @param description       free text
 * @param interventionLevel pure metadata — CAPRA reads it, logs it, and does nothing else
 *                          with it. It exists because it is archived with the run, and that
 *                          is what makes results attributable to a level
 * @param defaults          applied to every role not overridden
 * @param roles             per-role overrides, merged field by field over {@code defaults}
 * @param promptSetId       which prompt set was used; the set itself is archived separately
 * @param retry             retry policy, archived with the run rather than hidden in code
 */
public record Blueprint(
        String id,
        String description,
        InterventionLevel interventionLevel,
        RoleConfig defaults,
        Map<LlmRole, RoleConfig> roles,
        String promptSetId,
        RetryPolicy retry
) {
    /**
     * The effective configuration for a role: {@code defaults} overridden field by field by
     * the entry in {@code roles}. A role absent from {@code roles} uses the defaults whole.
     *
     * @param role the role to resolve
     * @return the merged configuration
     */
    public RoleConfig resolve(LlmRole role) {
        RoleConfig override = roles == null ? null : roles.get(role);
        return override == null ? defaults : override.mergedOver(defaults);
    }
}
