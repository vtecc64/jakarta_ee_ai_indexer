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

    public static void main(String[] args) {
        final int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    private static int run(String[] args) {
        Path repoRoot = null;
        Path outDir = null;
        boolean includeTests = true;
        final Set<String> moduleFilter = new LinkedHashSet<>();

        try {
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
            if (graph.parseWarnings() > 0) {
                System.err.println("WARN: parse warnings: " + graph.parseWarnings());
            }
            return 0;
        } catch (java.io.IOException ex) {
            System.err.println("ERROR: IO failure while writing graph: " + safeMsg(ex.getMessage()));
            return 2;
        } catch (Exception ex) {
            System.err.println("ERROR: failed to build graph: "
                    + ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage()));
            return 1;
        }
    }

    private static String safeMsg(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}
