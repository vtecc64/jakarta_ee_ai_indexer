package ai.indexer.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ai.indexer.graph.Graph;

public final class GraphWriter {

    public static final String SCHEMA_VERSION = "ai-graph/v2";

    private final Path outDir;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper jsonlMapper;

    public GraphWriter(Path outDir) {
        this.outDir = Objects.requireNonNull(outDir, "outDir");
        this.jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.jsonlMapper = new ObjectMapper();
    }

    public void writeAll(Graph graph, String generatedAt) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(generatedAt, "generatedAt");

        // Ensure directory exists
        Files.createDirectories(outDir);

        // Write per-module JSONL
        final List<String> moduleIds = new ArrayList<>(graph.modules().keySet());
        Collections.sort(moduleIds);

        final List<ModuleIndexEntry> moduleEntries = new ArrayList<>(moduleIds.size());
        final List<ModuleSummary> moduleSummaries = new ArrayList<>(moduleIds.size());
        int totalTypes = 0;
        int totalInjects = 0;
        int totalEjb = 0;

        for (String moduleId : moduleIds) {
            final Graph.ModuleFiles files = graph.modules().get(moduleId);

            final String typesName = "types." + moduleId + ".jsonl";
            final String injectName = "inject." + moduleId + ".jsonl";
            final String ejbName = "ejb." + moduleId + ".jsonl";

            writeJsonl(outDir.resolve(typesName), files.types());
            writeJsonl(outDir.resolve(injectName), files.inject());
            writeJsonl(outDir.resolve(ejbName), files.ejb());

            final int typesCount = files.types().size();
            final int injectsCount = files.inject().size();
            final int ejbCount = files.ejb().size();
            totalTypes += typesCount;
            totalInjects += injectsCount;
            totalEjb += ejbCount;
            moduleSummaries.add(new ModuleSummary(moduleId, typesCount, injectsCount, ejbCount));

            moduleEntries.add(new ModuleIndexEntry(moduleId, typesName, injectName, ejbName));
        }

        final Summary summary = new Summary(
                totalTypes,
                totalInjects,
                totalEjb,
                graph.parseWarnings(),
                moduleSummaries
        );

        // Write global indices
        writeJson(outDir.resolve("types.index.json"), new TreeMap<>(graph.typeIndex()));
        writeJson(outDir.resolve("ejb.index.json"), new TreeMap<>(graph.ejbIndex()));

        // Master index
        final MasterIndex idx = new MasterIndex(
                SCHEMA_VERSION,
                generatedAt,
                moduleEntries,
                "types.index.json",
                "ejb.index.json",
                summary
        );

        writeJson(outDir.resolve("index.json"), idx);
    }

    private void writeJson(Path file, Object data) throws IOException {
        jsonMapper.writeValue(file.toFile(), data);
    }

    private <T> void writeJsonl(Path file, List<T> lines) throws IOException {
        // overwrite each time (simple + deterministic)
        try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {

            for (T line : lines) {
                final String json = jsonlMapper.writeValueAsString(line);
                bw.write(json);
                bw.newLine();
            }
        }
    }

    // --- index records (written as JSON, not JSONL) ---

    public record MasterIndex(
            String schema,
            String generatedAt,
            List<ModuleIndexEntry> modules,
            String typeIndex,
            String ejbIndex,
            Summary summary
    ) {
    }

    public record ModuleIndexEntry(
            String id,
            String types,
            String inject,
            String ejb
    ) {
    }

    public record Summary(
            int totalTypes,
            int totalInjects,
            int totalEjb,
            int parseWarnings,
            List<ModuleSummary> modules
    ) {
    }

    public record ModuleSummary(
            String id,
            int types,
            int injects,
            int ejb
    ) {
    }
}
