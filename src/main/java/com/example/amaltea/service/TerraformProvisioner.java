package com.example.amaltea.service;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.LlmRole;
import com.example.amaltea.model.catalog.SlmModel;
import com.example.amaltea.model.infrastructure.VmInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Creates and tears down the GPU machines that serve the models.
 * <p>
 * Their lifecycle <em>wraps</em> the loop over documents: created once per configuration,
 * used for the whole corpus, then destroyed. Provisioning costs minutes, so repeating it
 * per document would be a large waste.
 * <p>
 * The exchange with the provisioning tool happens over files and the command line, with
 * {@code ProcessBuilder} as the glue — the same mechanism the system under test already
 * uses to drive its document compiler, so the pattern is one this project has validated.
 * <p>
 * <b>What is missing here is the infrastructure definition itself.</b> Instance type,
 * image, region and GPU availability differ completely between providers, and the choice
 * depends on the budget available. The protocol below is provider-independent and holds
 * whichever way that is decided.
 */
@Service
public class TerraformProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TerraformProvisioner.class);

    private static final String VARS_FILE = "terraform.tfvars.json";
    private static final int COMMAND_TIMEOUT_MINUTES = 30;

    private final AmalteaProperties properties;
    private final ObjectMapper mapper = ReferenceArchive.archiveMapper();
    private final HttpClient probe = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public TerraformProvisioner(AmalteaProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates one machine per <b>distinct model</b> in the assignment, not one per role.
     * <p>
     * Several roles may point at the same model, and the inference engine loads its
     * weights once while serving them all. In the uniform phase of the search — the bulk
     * of a campaign — all six roles share one model and a single machine suffices.
     *
     * @param workDir a directory reserved for this configuration. The tool keeps a record
     *                of existing resources there, and sharing it between configurations
     *                would make one teardown destroy another's machines — silently, since
     *                configurations are evaluated in sequence
     */
    public List<VmInstance> provision(Map<LlmRole, SlmModel> assignment, Path workDir) {
        Set<SlmModel> distinct = new LinkedHashSet<>(assignment.values());
        log.info("Provisioning: {} ruoli, {} modelli distinti, quindi {} macchine",
                assignment.size(), distinct.size(), distinct.size());

        writeVariables(distinct, workDir);
        run(workDir, "terraform", "init", "-input=false");
        run(workDir, "terraform", "apply", "-auto-approve", "-input=false");

        return readOutputs(workDir, distinct);
    }

    /**
     * Tears the machines down.
     * <p>
     * Callers must invoke this from a block that runs unconditionally. It is the one
     * failure in this system that costs money directly: an exception after a successful
     * provisioning would leave accelerators running and billing.
     */
    public void destroy(Path workDir) {
        try {
            run(workDir, "terraform", "destroy", "-auto-approve", "-input=false");
            log.info("Macchine dismesse");
        } catch (Exception e) {
            // Reported loudly rather than propagated: a teardown failure must not mask
            // whatever the caller was already handling, but it must not pass unnoticed.
            log.error("DISMISSIONE FALLITA in {} — verificare manualmente le risorse attive",
                    workDir, e);
        }
    }

    /**
     * Waits for a machine to answer.
     * <p>
     * Readiness is the model endpoint responding, not the machine booting: the weights
     * have to be loaded before any request can be served, and that is the longer part.
     */
    public boolean awaitReady(VmInstance instance, Duration timeout) {
        String url = "http://" + instance.ipAddress() + ":8000/v1/models";
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = probe.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {
                // Expected while the machine boots and the weights load.
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Reports resources left behind by earlier executions.
     * <p>
     * Invoked at the start of every campaign. Sooner or later something will fail in a way
     * that was not foreseen, and it is better that the system notices by itself than that
     * an invoice does.
     */
    public List<String> reapOrphans(Path root) {
        List<String> orphans = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return orphans;
        }
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("terraform.tfstate")))
                    .forEach(dir -> {
                        orphans.add(dir.getFileName().toString());
                        log.warn("Stato Terraform residuo in {}: possibili risorse attive", dir);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile esaminare " + root, e);
        }
        return orphans;
    }

    // ------------------------------------------------------------------ internals

    private void writeVariables(Set<SlmModel> models, Path workDir) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SlmModel model : models) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("model_id", model.modelId());
            entry.put("min_vram_gb", model.minVramGb());
            entries.add(entry);
        }
        try {
            Files.createDirectories(workDir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(workDir.resolve(VARS_FILE).toFile(), Map.of("models", entries));
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile scrivere " + VARS_FILE, e);
        }
    }

    private List<VmInstance> readOutputs(Path workDir, Set<SlmModel> models) {
        String json = run(workDir, "terraform", "output", "-json");
        List<VmInstance> instances = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode addresses = root.path("instances").path("value");
            int index = 0;
            for (SlmModel ignored : models) {
                JsonNode node = addresses.get(index++);
                if (node == null) {
                    continue;
                }
                instances.add(new VmInstance(
                        node.path("id").asText(""),
                        node.path("ip").asText(""),
                        node.path("gpu_type").asText(""),
                        new BigDecimal(node.path("hourly_cost").asText("0")),
                        LocalDateTime.now(), null, null));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Output di Terraform non interpretabile", e);
        }
        return instances;
    }

    private String run(Path workDir, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(false)
                    .start();

            String output = new String(process.getInputStream().readAllBytes());
            String errors = new String(process.getErrorStream().readAllBytes());

            if (!process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Comando scaduto: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "Comando fallito (%d): %s%n%s"
                                .formatted(process.exitValue(), String.join(" ", command), errors));
            }
            return output;

        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile eseguire " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Esecuzione interrotta", e);
        }
    }
}
