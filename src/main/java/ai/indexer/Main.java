package ai.indexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import ai.indexer.graph.GraphBuilder;
import ai.indexer.io.GraphWriter;
import ai.indexer.modules.ModuleLayout;

public final class Main {

    public static void main(String[] args) throws Exception {
        Path repoRoot = null;
        Path outDir = null;
        boolean includeTests = true;
        final Set<String> moduleFilter = new LinkedHashSet<>();

        for (String arg : args) {
            if (arg.startsWith("--outDir=")) {
                outDir = Paths.get(arg.substring("--outDir=".length()));
                continue;
            }
            if (arg.startsWith("--includeTests=")) {
                includeTests = Boolean.parseBoolean(arg.substring("--includeTests=".length()));
                continue;
            }
            if (arg.startsWith("--modules=")) {
                final String list = arg.substring("--modules=".length()).trim();
                if (!list.isEmpty()) {
                    Arrays.stream(list.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(moduleFilter::add);
                }
                continue;
            }
            if (!arg.startsWith("--") && repoRoot == null) {
                repoRoot = Paths.get(arg);
            }
        }

        if (repoRoot == null) {
            repoRoot = Paths.get(".");
        }
        repoRoot = repoRoot.toAbsolutePath().normalize();

        if (outDir == null) {
            outDir = repoRoot.resolve(".repo-ai");
        } else if (!outDir.isAbsolute()) {
            outDir = repoRoot.resolve(outDir).normalize();
        }
        Files.createDirectories(outDir);

        ModuleLayout layout = ModuleLayout.load(repoRoot);
        layout = layout.filterModules(moduleFilter);

        final GraphBuilder builder = new GraphBuilder(repoRoot, layout, includeTests);
        final var graph = builder.build(); // full scan (fast enough for you)

        final GraphWriter writer = new GraphWriter(outDir);
        writer.writeAll(graph, Instant.now().toString());

        System.out.println("AI graph written to: " + outDir);
        System.out.println("Modules: " + graph.modules().size()
                + ", types: " + graph.typeIndex().size()
                + ", EJB-ifaces: " + graph.ejbIndex().size());
    }
}
