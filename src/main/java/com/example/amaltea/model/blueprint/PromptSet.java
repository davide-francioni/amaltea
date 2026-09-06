package com.example.amaltea.model.blueprint;

import com.example.amaltea.model.InterventionLevel;
import com.example.amaltea.model.LlmRole;

import java.util.Map;

/**
 * The prompts CAPRA used in one execution.
 * <p>
 * Needed from {@link InterventionLevel#L1_PROMPTING} onwards. If prompts are rewritten for
 * small models but nobody records <em>which</em> prompts a given run used, the prompt
 * becomes a hidden variable and the results are neither reproducible nor comparable.
 * <p>
 * At L0 this holds CAPRA's original prompts, archived anyway.
 *
 * @param id                identifier, referenced by the blueprint
 * @param description       what distinguishes this set from the original one
 * @param interventionLevel the level this set was written for
 * @param prompts           system prompt per role; the value is plain text, not a class,
 *                          so in UML this is an attribute with a dependency on
 *                          {@link LlmRole}, not a qualified association
 */
public record PromptSet(
        String id,
        String description,
        InterventionLevel interventionLevel,
        Map<LlmRole, String> prompts
) {
    /** The prompt for a role, or {@code null} if this set does not override it. */
    public String promptFor(LlmRole role) {
        return prompts == null ? null : prompts.get(role);
    }
}
