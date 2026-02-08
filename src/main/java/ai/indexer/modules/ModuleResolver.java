package ai.indexer.modules;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a file path to a moduleId.
 * Strategy:
 * 1) Longest-prefix match against known module dirs from settings.gradle
 * 2) Fallback: directory segment before /src/(main|test|...)/... (your rule)
 * 3) "unknown"
 */
public final class ModuleResolver {

    private final Path repoRoot;
    private final Map<String, Path> moduleDirsById;

    public ModuleResolver(Path repoRoot, Map<String, Path> moduleDirsById) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.moduleDirsById = Objects.requireNonNull(moduleDirsById, "moduleDirsById");
    }

    public String resolveModuleId(Path file) {
        String best = null;
        int bestLen = -1;

        for (var e : moduleDirsById.entrySet()) {
            final String moduleId = e.getKey();
            final Path moduleDir = e.getValue();
            if (file.startsWith(moduleDir)) {
                final int len = moduleDir.getNameCount();
                if (len > bestLen) {
                    bestLen = len;
                    best = moduleId;
                }
            }
        }

        if (best != null) {
            return best;
        }

        // Fallback: folder before "src"
        final Path rel = repoRoot.relativize(file.toAbsolutePath().normalize());
        for (int i = 0; i < rel.getNameCount(); i++) {
            if ("src".equals(rel.getName(i).toString()) && i > 0) {
                return rel.getName(i - 1).toString();
            }
        }
        return "unknown";
    }
}
