package ai.indexer.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ai.indexer.model.Ids;

/**
 * Repository-wide symbol table for light resolution:
 * - fqcn -> exists
 * - simpleName -> fqcn (only if unique)
 */
public final class SymbolTable {

    private final Set<String> allFqcns = new HashSet<>();
    private final Map<String, String> uniqueSimpleToFqcn = new HashMap<>();
    private final Map<String, Integer> simpleCounts = new HashMap<>();

    public void registerType(String fqcn) {
        allFqcns.add(fqcn);
        final String simple = Ids.simpleNameOfFqcn(fqcn);
        simpleCounts.put(simple, simpleCounts.getOrDefault(simple, 0) + 1);
    }

    public void finalizeIndex() {
        // Build unique simpleName -> fqcn map
        // (requires another pass over all fqcns)
        for (String fqcn : allFqcns) {
            final String simple = Ids.simpleNameOfFqcn(fqcn);
            final int c = simpleCounts.getOrDefault(simple, 0);
            if (c == 1) {
                uniqueSimpleToFqcn.put(simple, fqcn);
            }
        }
    }

    public String resolveToFqcnIfPossible(String typeName, String packageName) {
        if (typeName == null || typeName.isBlank()) return null;

        final String trimmed = Ids.normalizeTypeName(typeName);
        if (trimmed.isBlank()) return null;

        // Already FQCN
        if (trimmed.indexOf('.') >= 0) {
            return allFqcns.contains(trimmed) ? trimmed : trimmed; // keep as-is
        }

        // Try same package
        if (packageName != null && !packageName.isBlank()) {
            final String candidate = packageName + "." + trimmed;
            if (allFqcns.contains(candidate)) return candidate;
        }

        // Unique simple name in repo?
        final String uniq = uniqueSimpleToFqcn.get(trimmed);
        if (uniq != null) return uniq;

        return null;
    }

    public String toTypeId(String typeName, String packageName) {
        final String fqcn = resolveToFqcnIfPossible(typeName, packageName);
        if (fqcn != null) return Ids.typeId(fqcn);
        // fallback to stable-ish reference
        final String trimmed = Ids.normalizeTypeName(typeName);
        return "t:" + trimmed;
    }

    public boolean isKnownFqcn(String fqcn) {
        return allFqcns.contains(fqcn);
    }
}
