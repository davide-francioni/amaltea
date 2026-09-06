package com.example.amaltea.service;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.process.CapraStandardOutput;
import com.example.amaltea.model.process.ReferenceVariance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The standard outputs of CAPRA, on disk.
 * <p>
 * Kept separate from {@link ProcessArchive} because references have a different lifecycle:
 * they live <em>beyond</em> the single process. They depend only on the document and the
 * baseline blueprint — not on the intervention level, not on the family — so they are
 * generated once and reused by every process that follows.
 *
 * <pre>
 * &lt;archivePath&gt;/references/&lt;documentId&gt;/
 *     run-1.json  run-2.json  run-3.json
 *     variance.json
 * </pre>
 */
@Service
public class ReferenceArchive {

    private static final String REFERENCES = "references";
    private static final String VARIANCE_FILE = "variance.json";

    private final ObjectMapper mapper;
    private final Path root;

    public ReferenceArchive(AmalteaProperties properties) {
        this.mapper = archiveMapper();
        this.root = properties.archivePath().resolve(REFERENCES);
    }

    /**
     * The archive owns its serialization format rather than sharing the application's mapper.
     * <p>
     * A reference is meant to be compared months later: if someone reconfigured a shared
     * {@code ObjectMapper} — a different date format, say — files written before and after would
     * differ for reasons that have nothing to do with the experiment. Dates go out as ISO strings
     * rather than numeric timestamps, so the files stay readable by a person.
     */
    static ObjectMapper archiveMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Writes one repetition of a document's reference.
     *
     * @param output the standard output; {@code repetitionIndex} names the file
     * @return where it was written
     */
    public Path save(CapraStandardOutput output) {
        Path target = documentDir(output.documentId())
                .resolve("run-%d.json".formatted(output.repetitionIndex()));
        write(target, output);
        return target;
    }

    /** Writes the noise floor computed across the repetitions. */
    public Path saveVariance(ReferenceVariance variance) {
        Path target = documentDir(variance.documentId()).resolve(VARIANCE_FILE);
        write(target, variance);
        return target;
    }

    /**
     * Every repetition stored for a document, ordered by repetition index.
     *
     * @return the repetitions, empty if the document has no reference yet
     */
    public List<CapraStandardOutput> load(String documentId) {
        Path dir = root.resolve(documentId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<CapraStandardOutput> outputs = new ArrayList<>();
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith("run-"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> outputs.add(read(p, CapraStandardOutput.class)));
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile leggere i riferimenti di " + documentId, e);
        }
        return outputs;
    }

    /** The noise floor for a document, if it has been computed. */
    public Optional<ReferenceVariance> loadVariance(String documentId) {
        Path target = root.resolve(documentId).resolve(VARIANCE_FILE);
        return Files.exists(target)
                ? Optional.of(read(target, ReferenceVariance.class))
                : Optional.empty();
    }

    /**
     * Whether the stored reference is still comparable with what CAPRA would produce now.
     * <p>
     * References stop being valid when CAPRA changes underneath them: a refactoring, or an
     * edit to the knowledge base, makes them incomparable — and every later divergence would
     * be blamed on the SLM. Checking costs nothing when they are valid, and protects against
     * an error that would otherwise be invisible.
     *
     * @param documentId          which document
     * @param baselineBlueprintId the baseline configuration in use now
     * @return {@code true} if the stored reference was produced with that same configuration
     */
    public boolean isStillValid(String documentId, String baselineBlueprintId) {
        List<CapraStandardOutput> stored = load(documentId);
        return !stored.isEmpty()
                && stored.stream().allMatch(o -> baselineBlueprintId.equals(o.baselineBlueprintId()));
    }

    /** Documents that already have at least one reference. */
    public List<String> documentsWithReferences() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile elencare i riferimenti", e);
        }
    }

    private Path documentDir(String documentId) {
        Path dir = root.resolve(documentId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile creare " + dir, e);
        }
        return dir;
    }

    private void write(Path target, Object value) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile scrivere " + target, e);
        }
    }

    private <T> T read(Path source, Class<T> type) {
        try {
            return mapper.readValue(source.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile leggere " + source, e);
        }
    }
}
