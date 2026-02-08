package ai.indexer.model;

import java.util.Objects;

public final class Ids {

    private Ids() {
    }

    public static String typeId(String fqcn) {
        Objects.requireNonNull(fqcn, "fqcn");
        return "t:" + fqcn;
    }

    public static String fieldId(String ownerFqcn, String fieldName) {
        Objects.requireNonNull(ownerFqcn, "ownerFqcn");
        Objects.requireNonNull(fieldName, "fieldName");
        return "f:" + ownerFqcn + "#" + fieldName;
    }

    public static String methodId(String ownerFqcn, String methodSignature) {
        Objects.requireNonNull(ownerFqcn, "ownerFqcn");
        Objects.requireNonNull(methodSignature, "methodSignature");
        return "m:" + ownerFqcn + "#" + methodSignature;
    }

    public static String normalizeTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        final String raw = typeName.trim();
        if (raw.isEmpty()) {
            return raw;
        }

        final StringBuilder sb = new StringBuilder(raw.length());
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '<') {
                depth++;
                continue;
            }
            if (c == '>') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0) {
                sb.append(c);
            }
        }

        String normalized = sb.toString().trim();
        while (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2).trim();
        }
        if (normalized.endsWith("...")) {
            normalized = normalized.substring(0, normalized.length() - 3).trim();
        }
        return normalized;
    }

    public static String simpleNameOfFqcn(String fqcn) {
        final int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }
}
