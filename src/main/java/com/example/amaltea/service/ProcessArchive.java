package com.example.amaltea.service;

import com.example.amaltea.config.AmalteaProperties;
import com.example.amaltea.model.blueprint.PromptSet;
import com.example.amaltea.model.catalog.SlmCatalog;
import com.example.amaltea.model.process.BenchmarkProcess;
import com.example.amaltea.model.process.BenchmarkRun;
import com.example.amaltea.model.process.ConfigurationTrial;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Processes, trials and runs, on disk. AMALTEA uses no database.
 * <p>
 * The choice is coherent with the project: the notion of a <em>frozen snapshot</em> is already
 * central, and on files it becomes literal — one folder per process, which can be copied,
 * attached to the thesis, or compared with {@code diff} months later.
 *
 * <pre>
 * &lt;archivePath&gt;/processes/&lt;processId&gt;/
 *     process.json          metadata, intervention level, outcome
 *     catalog.json          frozen snapshot of the SLM catalogue
 *     knowledge-base.json   export of CAPRA's KB — an experimental variable, not plumbing
 *     prompts/&lt;promptSetId&gt;.json
 *     trials/&lt;trialId&gt;/
 *         trial.json        aggregate verdict
 *         blueprint.yaml    the configuration handed to CAPRA
 *         runs/&lt;runId&gt;/
 *             run.json      outcome, metrics, verdict
 *             report.json   the AuditReport CAPRA returned
 *             report.tex    LaTeX source, needed for check F3
 * </pre>
 */
@Service
public class ProcessArchive {

    private static final String PROCESSES = "processes";

    private final ObjectMapper mapper;
    private final Path root;

    public ProcessArchive(AmalteaProperties properties) {
        this.mapper = ReferenceArchive.archiveMapper();
        this.root = properties.archivePath().resolve(PROCESSES);
    }

    /** Writes the process metadata and its frozen catalogue snapshot. */
    public Path save(BenchmarkProcess process) {
        Path dir = processDir(process.id());
        write(dir.resolve("process.json"), process);
        if (process.catalog() != null) {
            write(dir.resolve("catalog.json"), process.catalog());
        }
        return dir;
    }

    /**
     * Writes a trial's aggregate verdict.
     * <p>
     * The blueprint is written separately and <b>before</b> execution (see
     * {@link #blueprintPath}): a trial that fails halfway must still leave a record of the
     * configuration it was attempted with.
     */
    public Path saveTrial(String processId, ConfigurationTrial trial) {
        Path target = trialDir(processId, trial.id()).resolve("trial.json");
        write(target, trial);
        return target;
    }

    /** Writes one run's outcome, metrics and verdict. */
    public Path saveRun(String processId, String trialId, BenchmarkRun run) {
        Path target = runDir(processId, trialId, run.id()).resolve("run.json");
        write(target, run);
        return target;
    }

    /**
     * Copies CAPRA's artefacts into the run folder.
     * <p>
     * <b>Copied, not referenced.</b> CAPRA writes into its own output directory, relative to
     * its working directory and filling up with every execution. Pointing at those files would
     * make the archive depend on a folder CAPRA manages for itself; copying makes each run
     * self-contained, which is what lets the folder be attached to the thesis or reread in a
     * year.
     *
     * @param auditReportJson the structured report, as returned
     * @param latexSource     the LaTeX source, or {@code null} if the role produced none
     */
    public void saveCapraArtifacts(String processId, String trialId, String runId,
                                   String auditReportJson, String latexSource) {
        Path dir = runDir(processId, trialId, runId);
        writeText(dir.resolve("report.json"), auditReportJson);
        if (latexSource != null && !latexSource.isBlank()) {
            writeText(dir.resolve("report.tex"), latexSource);
        }
    }

    /**
     * Where the blueprint of a trial goes.
     * <p>
     * Written before execution by the generator. Note that the archived file <b>documents</b>
     * what was run but is not re-executable as such: it holds the addresses of ephemeral VMs,
     * destroyed when the trial ends. What survives is the substance — models per role, options,
     * prompt set, retry policy — while the addresses would have to be regenerated.
     */
    public Path blueprintPath(String processId, String trialId) {
        return trialDir(processId, trialId).resolve("blueprint.yaml");
    }

    /** Archives the prompt set used, so the prompts never become a hidden variable. */
    public Path savePromptSet(String processId, PromptSet promptSet) {
        Path dir = processDir(processId).resolve("prompts");
        createDirs(dir);
        Path target = dir.resolve(promptSet.id() + ".json");
        write(target, promptSet);
        return target;
    }

    /**
     * Archives CAPRA's knowledge base with the process.
     * <p>
     * That collection decides <em>what the FeatureCheckAgent looks for</em>, so it is an
     * experimental variable rather than infrastructure. If it changed between generating the
     * references and running the SLMs, the difference would be attributed to the model while
     * coming from the knowledge base — a silent error, and one nearly impossible to diagnose
     * afterwards without this snapshot.
     */
    public Path saveKnowledgeBaseSnapshot(String processId, String knowledgeBaseJson) {
        Path target = processDir(processId).resolve("knowledge-base.json");
        writeText(target, knowledgeBaseJson);
        return target;
    }

    /** Reads back a process, for a longitudinal comparison or to inherit its catalogue. */
    public BenchmarkProcess load(String processId) {
        return read(processDir(processId).resolve("process.json"), BenchmarkProcess.class);
    }

    /**
     * The catalogue a previous process resolved.
     * <p>
     * Used when a process inherits its catalogue instead of discovering a fresh one: comparing
     * the L0 threshold with the L1 threshold requires that both processes had the same
     * candidates, and that comparison is the central result of the thesis.
     */
    public SlmCatalog loadCatalog(String processId) {
        return read(processDir(processId).resolve("catalog.json"), SlmCatalog.class);
    }

    /** Every archived process, most recent last. */
    public List<String> listProcesses() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile elencare i processi", e);
        }
    }

    private Path processDir(String processId) {
        Path dir = root.resolve(processId);
        createDirs(dir);
        return dir;
    }

    private Path trialDir(String processId, String trialId) {
        Path dir = processDir(processId).resolve("trials").resolve(trialId);
        createDirs(dir);
        return dir;
    }

    private Path runDir(String processId, String trialId, String runId) {
        Path dir = trialDir(processId, trialId).resolve("runs").resolve(runId);
        createDirs(dir);
        return dir;
    }

    private void createDirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile creare " + dir, e);
        }
    }

    private void write(Path target, Object value) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Impossibile scrivere " + target, e);
        }
    }

    private void writeText(Path target, String content) {
        try {
            Files.writeString(target, content);
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
