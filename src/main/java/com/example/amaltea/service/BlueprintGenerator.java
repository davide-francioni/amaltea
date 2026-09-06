package com.example.amaltea.service;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.InterventionLevel;
import com.example.amaltea.model.LlmRole;
import com.example.amaltea.model.blueprint.Blueprint;
import com.example.amaltea.model.blueprint.PromptSet;
import com.example.amaltea.model.blueprint.Provider;
import com.example.amaltea.model.blueprint.RetryPolicy;
import com.example.amaltea.model.blueprint.RoleConfig;
import com.example.amaltea.model.catalog.SlmModel;
import com.example.amaltea.model.infrastructure.VmInstance;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the configuration handed to CAPRA at startup, one per execution.
 * <p>
 * The unit of configuration is the <b>role</b>, not the provider and not the Java class: that
 * is what keeps blueprints valid even if CAPRA reorganises its code internally.
 */
@Service
public class BlueprintGenerator {

    /** Deterministic settings CAPRA was validated with; kept identical across the campaign. */
    private static final Map<String, Object> DETERMINISTIC_OPTIONS =
            Map.of("temperature", 0.0, "seed", 42);

    private final AmalteaProperties properties;
    private final ObjectMapper yamlMapper;

    public BlueprintGenerator(AmalteaProperties properties) {
        this.properties = properties;
        this.yamlMapper = new ObjectMapper(
                new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    }

    /**
     * How many machines a trial needs: <b>one per distinct model, not one per role.</b>
     * <p>
     * «N SLMs per agent» means every role has its own assignment, independent of the others —
     * not that there are six different models. Several roles may point at the same SLM, and
     * vLLM loads the weights once while serving all of them. In the uniform descent all six
     * roles share one model, so a single GPU suffices: the cost of that phase, which is the
     * bulk of the campaign, is a sixth of what «six roles, six VMs» would suggest.
     *
     * @param assignment the six role assignments; values may repeat
     * @return the distinct models to serve, in first-appearance order
     */
    public Set<SlmModel> requiredVms(Map<LlmRole, SlmModel> assignment) {
        return new LinkedHashSet<>(assignment.values());
    }

    /**
     * Builds the blueprint for one trial.
     *
     * @param assignment which model answers for which role — always six entries
     * @param placement  which machine serves each distinct model, from {@link #requiredVms}
     * @param level      the intervention level of the process; pure metadata for CAPRA, but
     *                   archived with the run and therefore what makes results attributable
     * @param promptSet  the prompts in force; at L0 these are CAPRA's originals, archived anyway
     * @return the blueprint, ready to be written and handed over
     */
    public Blueprint generate(Map<LlmRole, SlmModel> assignment,
                              Map<SlmModel, VmInstance> placement,
                              InterventionLevel level,
                              PromptSet promptSet) {

        Map<LlmRole, RoleConfig> roles = new EnumMap<>(LlmRole.class);
        for (Map.Entry<LlmRole, SlmModel> entry : assignment.entrySet()) {
            SlmModel model = entry.getValue();
            VmInstance vm = placement.get(model);
            if (vm == null) {
                throw new IllegalArgumentException(
                        "Nessuna VM assegnata al modello " + model.modelId()
                                + " richiesto dal ruolo " + entry.getKey());
            }
            roles.put(entry.getKey(), new RoleConfig(
                    Provider.OPENAI_COMPATIBLE,
                    // No trailing path: the client appends the completions path itself,
                    // so the base URL must stop at host and port. Adding "/v1" here
                    // would produce a doubled segment in the request URL — an error that
                    // would only surface once real machines exist, and would then look
                    // like a networking problem.
                    "http://" + vm.ipAddress() + ":8000",
                    "not-required",
                    model.modelId(),
                    properties.baseline().timeoutSeconds(),
                    DETERMINISTIC_OPTIONS
            ));
        }

        RoleConfig defaults = new RoleConfig(
                Provider.OPENAI_COMPATIBLE, null, "not-required", null,
                properties.baseline().timeoutSeconds(), DETERMINISTIC_OPTIONS);

        return new Blueprint(
                "trial-" + UUID.randomUUID(),
                describe(assignment, level),
                level,
                defaults,
                roles,
                promptSet == null ? null : promptSet.id(),
                RetryPolicy.benchmarkDefault()
        );
    }

    /**
     * The baseline configuration — GPT-5.1 for the five analysis roles, Claude Haiku for the
     * LaTeX role — used to produce the standard outputs.
     * <p>
     * Values come from {@link AmalteaProperties.Baseline}, which must be filled from CAPRA's
     * {@code application.yaml} and source, <b>never from its README</b>: the README diverges
     * from the real configuration on three points, and compiling the baseline from it would
     * silently produce a reference different from the one CAPRA was validated with.
     */
    public Blueprint generateBaseline() {
        AmalteaProperties.Baseline b = properties.baseline();

        RoleConfig openai = new RoleConfig(
                Provider.OPENAI, b.openaiBaseUrl(), b.openaiApiKey(), b.openaiModel(),
                b.timeoutSeconds(), DETERMINISTIC_OPTIONS);

        RoleConfig anthropic = new RoleConfig(
                Provider.ANTHROPIC, b.anthropicBaseUrl(), b.anthropicApiKey(), b.anthropicModel(),
                b.timeoutSeconds(), DETERMINISTIC_OPTIONS);

        Map<LlmRole, RoleConfig> roles = new EnumMap<>(LlmRole.class);
        roles.put(LlmRole.LATEX_REPORT, anthropic);

        return new Blueprint(
                "baseline-original",
                "Configurazione originale CAPRA — generazione degli Standard Output",
                InterventionLevel.L0_SOSTITUZIONE,
                openai,
                roles,
                null,
                RetryPolicy.benchmarkDefault()
        );
    }

    /**
     * Writes the blueprint to YAML.
     * <p>
     * Written <b>before</b> execution, so that a trial failing halfway still leaves a record of
     * the configuration it was attempted with.
     */
    public Path writeYaml(Blueprint blueprint, Path target) {
        try {
            yamlMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), blueprint);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile scrivere il blueprint in " + target, e);
        }
    }

    private String describe(Map<LlmRole, SlmModel> assignment, InterventionLevel level) {
        Set<SlmModel> distinct = requiredVms(assignment);
        return distinct.size() == 1
                ? "%s — %s su tutti i ruoli".formatted(level, distinct.iterator().next().name())
                : "%s — assegnazione mista su %d modelli".formatted(level, distinct.size());
    }
}
