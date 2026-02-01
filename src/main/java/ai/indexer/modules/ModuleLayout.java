package ai.indexer.modules;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads Gradle modules from settings.gradle.
 * Robust against multiline 'include' blocks by extracting module tokens from all lines.
 */
public final class ModuleLayout {

    // Matches ':moduleName' within single quotes, e.g. ':contentmanager'
    private static final Pattern MODULE_TOKEN = Pattern.compile("':([^']+)'");

    private static final Pattern PROJECT_DIR = Pattern.compile(
            "project\\('\\:([^']+)'\\)\\.projectDir\\s*=\\s*new\\s+File\\(settingsDir,\\s*'([^']+)'\\)"
    );

    private final Path repoRoot;
    private final Map<String, Path> moduleDirsById;

    private ModuleLayout(Path repoRoot, Map<String, Path> moduleDirsById) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.moduleDirsById = Map.copyOf(moduleDirsById);
    }

    public static ModuleLayout load(Path repoRoot) throws IOException {
        Objects.requireNonNull(repoRoot, "repoRoot");
        final Path settings = repoRoot.resolve("settings.gradle");
        if (!Files.exists(settings)) {
            return new ModuleLayout(repoRoot, Map.of());
        }

        // LinkedHashSet preserves encounter order (nice for determinism/debugging)
        final Set<String> moduleIds = new LinkedHashSet<>();
        final Map<String, String> projectDirs = new HashMap<>();

        try (BufferedReader br = Files.newBufferedReader(settings, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {

                // 1) Collect module tokens from ANY line (handles multiline include blocks)
                final Matcher m = MODULE_TOKEN.matcher(line);
                while (m.find()) {
                    moduleIds.add(m.group(1));
                }

                // 2) Collect explicit projectDir mappings
                final Matcher pd = PROJECT_DIR.matcher(line);
                if (pd.find()) {
                    final String moduleId = pd.group(1);
                    final String dir = pd.group(2);
                    projectDirs.put(moduleId, dir);
                }
            }
        }

        final Map<String, Path> out = new HashMap<>();
        for (String moduleId : moduleIds) {
            final String dir = projectDirs.getOrDefault(moduleId, moduleId);
            final Path abs = repoRoot.resolve(dir).toAbsolutePath().normalize();
            out.put(moduleId, abs);
        }

        // Don't index the indexer itself unless you explicitly want it
        out.remove("ai-indexer");

        return new ModuleLayout(repoRoot, out);
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public Map<String, Path> moduleDirsById() {
        return moduleDirsById;
    }

    public List<String> moduleIdsSorted() {
        final List<String> ids = new ArrayList<>(moduleDirsById.keySet());
        Collections.sort(ids);
        return ids;
    }

    public ModuleLayout filterModules(Set<String> moduleIds) {
        if (moduleIds == null || moduleIds.isEmpty()) {
            return this;
        }
        final Map<String, Path> filtered = new HashMap<>();
        for (String moduleId : moduleIds) {
            final Path dir = moduleDirsById.get(moduleId);
            if (dir != null) {
                filtered.put(moduleId, dir);
            }
        }
        return new ModuleLayout(repoRoot, filtered);
    }
}
