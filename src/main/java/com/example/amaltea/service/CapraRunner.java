package com.example.amaltea.service;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.blueprint.Blueprint;
import com.example.amaltea.model.execution.CapraInstance;
import com.example.amaltea.model.execution.CapraRunResult;
import com.example.amaltea.model.execution.RetryEvent;
import com.example.amaltea.model.process.TestDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches the system under test, submits documents to it, and shuts it down.
 * <p>
 * One process per configuration, started with its own blueprint and terminated when the
 * configuration has been evaluated. Keeping a single process alive and reconfiguring it
 * would be faster, but would leave open the possibility that caches, pooled connections
 * or residual state from one run influence the next — an undeclared source of variability,
 * hard to exclude during analysis and harder still to diagnose if it ever showed. Startup
 * costs seconds against runs that take minutes: for work that produces measurements, that
 * cleanliness is worth more than the speed.
 */
@Service
public class CapraRunner {

    private static final Logger log = LoggerFactory.getLogger(CapraRunner.class);

    /** Failures on this side of the exchange, distinct from any status CAPRA could return. */
    private static final int STATUS_NO_RESPONSE = 0;

    private final RestClient client;
    private final AmalteaProperties properties;
    private final ObjectMapper mapper;

    public CapraRunner(RestClient capraRestClient, AmalteaProperties properties) {
        this.client = capraRestClient;
        this.properties = properties;
        this.mapper = ReferenceArchive.archiveMapper();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Starts an instance configured by the given blueprint and waits for it to answer.
     *
     * @param blueprintFile the YAML already written to disk; it is passed by path rather
     *                      than by value because it must survive as the archived record
     *                      of what was run
     * @return the running instance
     * @throws IllegalStateException if it does not become ready within the budget
     */
    public CapraInstance start(Blueprint blueprint, Path blueprintFile) {
        AmalteaProperties.Capra capra = properties.capra();

        ProcessBuilder builder = new ProcessBuilder(
                "java",
                "-jar", properties.capraJarPath().toString(),
                "--blueprint.file=" + blueprintFile.toAbsolutePath());

        // The working directory determines where CAPRA writes its own output. Setting it
        // here is why no change to CAPRA was needed to keep runs from piling up together.
        builder.directory(properties.archivePath().toFile());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Impossibile avviare CAPRA da " + properties.capraJarPath(), e);
        }

        CapraInstance instance = new CapraInstance(
                process.pid(), portOf(capra.baseUrl()), LocalDateTime.now());

        log.info("CAPRA avviato (pid {}) con blueprint '{}'", instance.pid(), blueprint.id());

        if (!awaitReady(Duration.ofSeconds(capra.startupTimeoutSeconds()))) {
            stop(instance);
            throw new IllegalStateException(
                    "CAPRA non è diventato disponibile entro "
                            + capra.startupTimeoutSeconds() + " secondi");
        }
        return instance;
    }

    /**
     * Terminates the instance, forcing it if it does not stop on its own.
     * <p>
     * Invoked from a block that runs unconditionally: an orphaned process would hold the
     * port and make the next configuration fail to start, with nothing to indicate why.
     */
    public void stop(CapraInstance instance) {
        ProcessHandle.of(instance.pid()).ifPresent(handle -> {
            handle.destroy();
            try {
                if (!handle.onExit().toCompletableFuture()
                        .get(properties.capra().shutdownGraceSeconds(), TimeUnit.SECONDS)
                        .isAlive()) {
                    log.info("CAPRA (pid {}) terminato", instance.pid());
                    return;
                }
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            handle.destroyForcibly();
            log.warn("CAPRA (pid {}) terminato forzatamente", instance.pid());
        });
    }

    /**
     * Whether the instance answers and its dependencies are up.
     * <p>
     * A degraded status is treated as not ready. CAPRA needs the document extraction
     * service, and starting a configuration without it would produce failures attributed
     * to the model that in fact belong to the infrastructure.
     */
    public boolean awaitReady(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                String body = client.get().uri("/api/health").retrieve().body(String.class);
                JsonNode health = mapper.readTree(body);
                if ("ok".equals(health.path("status").asText())) {
                    return true;
                }
                log.debug("CAPRA risponde ma è in stato '{}'", health.path("status").asText());
            } catch (Exception ignored) {
                // Not up yet: expected while the JVM and the Spring context start.
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ execution

    /**
     * Submits one document and collects everything the execution produced.
     * <p>
     * <b>Failures are returned, not thrown.</b> A run that produced no document is a
     * legitimate outcome to record — very possibly the outcome the experiment is looking
     * for — so it comes back as a result carrying the reason. Only faults on this side
     * would be exceptional, and those too are converted here so that a single unusable
     * document cannot abort a campaign that has already cost hours.
     */
    public CapraRunResult analyze(TestDocument document) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new FileSystemResource(document.path()));

        Instant started = Instant.now();
        try {
            ResponseEntity<String> response = client.post()
                    .uri("/api/analyze/full")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return CapraRunResult.failed(response.getStatusCode().value(), false,
                        "Risposta non riuscita da CAPRA");
            }
            return parse(response.getStatusCode().value(), response.getBody());

        } catch (Exception e) {
            boolean timedOut = exceededBudget(started) || isTimeout(e);
            log.warn("Analisi di '{}' non riuscita ({}): {}",
                    document.filename(), timedOut ? "timeout" : "errore", rootMessage(e));
            return CapraRunResult.failed(STATUS_NO_RESPONSE, timedOut, rootMessage(e));
        }
    }

    /**
     * Exports the expected-feature knowledge base as it stands.
     * <p>
     * Returned as raw text and handed to the archive verbatim. That collection decides
     * what the feature-check agent looks for, so it is an input of the experiment rather
     * than infrastructure: if it changed between generating the references and running the
     * campaign, the divergence would be attributed to the model while originating here.
     * <p>
     * Fetched over HTTP rather than read from CAPRA's database, so that AMALTEA needs
     * neither a database driver nor someone else's credentials to read a list.
     */
    public String fetchKnowledgeBase() {
        return client.get()
                .uri("/api/knowledge-base")
                .retrieve()
                .body(String.class);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Reads the response envelope, keeping the report itself as raw text.
     * <p>
     * The envelope fields are extracted by name rather than by deserializing into types
     * mirrored from CAPRA: AMALTEA only has to compare that report, not build or
     * manipulate it, and a mirrored copy would be a second definition to keep aligned by
     * hand.
     */
    private CapraRunResult parse(int status, String body) {
        try {
            JsonNode root = mapper.readTree(body);

            JsonNode report = root.path("report");
            String reportJson = report.isMissingNode() || report.isNull()
                    ? null
                    : mapper.writeValueAsString(report);

            List<RetryEvent> retries = root.has("retryEvents") && !root.path("retryEvents").isNull()
                    ? mapper.convertValue(root.path("retryEvents"), new TypeReference<>() {})
                    : List.of();

            return new CapraRunResult(
                    status,
                    reportJson,
                    root.path("texGenerated").asBoolean(false),
                    root.path("pdfGenerated").asBoolean(false),
                    root.path("latexSource").asText(null),
                    false,
                    retries,
                    root.path("compileError").asText(null));

        } catch (IOException e) {
            // A malformed envelope is a failure of the exchange, not of the model: it is
            // recorded as such rather than being allowed to look like a model outcome.
            return CapraRunResult.failed(status, false,
                    "Risposta di CAPRA non interpretabile: " + e.getMessage());
        }
    }

    private boolean exceededBudget(Instant started) {
        return Duration.between(started, Instant.now())
                .compareTo(Duration.ofSeconds(properties.capra().runTimeoutSeconds())) >= 0;
    }

    private boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName().toLowerCase();
            if (name.contains("timeout")) {
                return true;
            }
        }
        return false;
    }

    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private int portOf(String baseUrl) {
        try {
            return java.net.URI.create(baseUrl).getPort();
        } catch (Exception e) {
            return -1;
        }
    }
}
