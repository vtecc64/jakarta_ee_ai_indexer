package ai.indexer.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds all Java source roots for each module:
 * - <module>/src/main/java
 * - <module>/src/test/java
 * - <module>/src/* /java  (generic - covers integrationTest, etc.)
 */
public final class SourceRootFinder {

    private final Map<String, Path> moduleDirsById;
    private final boolean includeTests;

    public SourceRootFinder(Map<String, Path> moduleDirsById, boolean includeTests) {
        this.moduleDirsById = Objects.requireNonNull(moduleDirsById, "moduleDirsById");
        this.includeTests = includeTests;
    }

    public Map<String, List<Path>> findAllSourceRoots() throws IOException {
        final Map<String, List<Path>> out = new HashMap<>();

        for (var e : moduleDirsById.entrySet()) {
            final String moduleId = e.getKey();
            final Path moduleDir = e.getValue();
            if (!Files.isDirectory(moduleDir)) continue;

            final List<Path> roots = new ArrayList<>();
            Files.walkFileTree(moduleDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip typical heavy dirs
                    final String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (".git".equals(name) || ".idea".equals(name) || "build".equals(name) || "out".equals(name) || "node_modules".equals(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Match .../src/<something>/java
                    if (looksLikeJavaSourceRoot(dir)) {
                        roots.add(dir);
                        return FileVisitResult.SKIP_SUBTREE; // no need to walk below; TypeScanner will handle files
                    }

                    return FileVisitResult.CONTINUE;
                }
            });

            out.put(moduleId, roots);
        }

        return out;
    }

    private boolean looksLikeJavaSourceRoot(Path dir) {
        // .../src/main/java or .../src/test/java or .../src/<any>/java
        final int n = dir.getNameCount();
        if (n < 3) return false;
        final String last = dir.getName(n - 1).toString();
        final String mid = dir.getName(n - 2).toString();
        final String src = dir.getName(n - 3).toString();
        if (!"java".equals(last) || !"src".equals(src) || mid.isBlank()) return false;
        return includeTests || "main".equals(mid);
    }
}
