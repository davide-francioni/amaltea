package com.example.amaltea.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Configuration of the benchmarking platform.
 * <p>
 * {@code referenceRepetitions} and {@code failFastThreshold} are methodological parameters
 * rather than technical ones. Keeping them in configuration instead of in the code means they
 * can be changed without recompiling and, more importantly, that they are archived with the
 * process they belong to — six months later a results file should say not only what happened
 * but under which rules.
 *
 * @param datasetPath          folder holding the ten test PDFs
 * @param archivePath          root of the file archive; AMALTEA uses no database
 * @param capraJarPath         the CAPRA jar to launch as a subprocess
 * @param terraformModulePath  Terraform modules for GPU VM provisioning
 * @param referenceRepetitions how many times the standard output is generated per document.
 *                             Three: on the baseline APIs that is about $13 against $4.40 for a
 *                             single pass, and without repetitions there is no way to tell an
 *                             SLM's shortcoming from the reference's own run-to-run noise
 * @param failFastThreshold    how many failed documents cut a trial short, sparing the
 *                             remaining runs and the VM time. An efficiency parameter, not a
 *                             correctness one: set it conservatively and refine later
 * @param baseline             the original CAPRA configuration, used to produce the standard
 *                             outputs
 * @param capra                how to launch and reach the system under test
 */
@ConfigurationProperties(prefix = "amaltea")
public record AmalteaProperties(
        Path datasetPath,
        Path archivePath,
        Path capraJarPath,
        Path terraformModulePath,
        int referenceRepetitions,
        int failFastThreshold,
        Baseline baseline,
        Capra capra
) {
    /**
     * How AMALTEA launches and reaches the system under test.
     *
     * @param baseUrl                 where the launched instance answers
     * @param startupTimeoutSeconds   how long to wait for it to become ready. Covers JVM
     *                                start, Spring context, and the model client registry
     * @param runTimeoutSeconds       budget for a single document analysis. Exceeding it is
     *                                a hard failure, and it is detected <em>here</em>:
     *                                CAPRA cannot report a timeout it never noticed
     * @param shutdownGraceSeconds    how long to wait for a clean stop before forcing it
     */
    public record Capra(
            String baseUrl,
            int startupTimeoutSeconds,
            int runTimeoutSeconds,
            int shutdownGraceSeconds
    ) {}

    /**
     * CAPRA's original configuration — GPT-5.1 for the five analysis roles, Claude Haiku for
     * the LaTeX role.
     * <p>
     * <b>These values must be taken from CAPRA's {@code application.yaml} and source, never
     * from its README:</b> the README diverges from the real configuration on three points,
     * including the Haiku model date. Compiling the baseline from the documentation would
     * silently produce a reference different from the one CAPRA was validated with.
     * <p>
     * They live in configuration rather than in code so that the reference is reproducible and
     * the values are archived with the process.
     *
     * @param openaiModel     model id for the five analysis roles
     * @param openaiBaseUrl   OpenAI endpoint
     * @param openaiApiKey    real key; the standard outputs run against the real APIs
     * @param anthropicModel  model id for {@code LATEX_REPORT}
     * @param anthropicBaseUrl Anthropic endpoint
     * @param anthropicApiKey real key
     * @param timeoutSeconds  per-request budget
     */
    public record Baseline(
            String openaiModel,
            String openaiBaseUrl,
            String openaiApiKey,
            String anthropicModel,
            String anthropicBaseUrl,
            String anthropicApiKey,
            int timeoutSeconds
    ) {}
}
