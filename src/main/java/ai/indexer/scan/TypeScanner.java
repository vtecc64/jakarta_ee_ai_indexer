package ai.indexer.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import ai.indexer.graph.SymbolTable;
import ai.indexer.model.Ids;

public final class TypeScanner {

    private final Path repoRoot;
    private final JavaParser parser;
    private int parseWarnings;

    public TypeScanner(Path repoRoot) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    public void scan(Path sourceRoot,
                     String moduleId,
                     Map<String, List<ScannedType>> scannedByModule,
                     Map<String, List<ScannedInjection>> injectionsByModule,
                     SymbolTable symbols) throws IOException {

        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(scannedByModule, "scannedByModule");
        Objects.requireNonNull(injectionsByModule, "injectionsByModule");
        Objects.requireNonNull(symbols, "symbols");

        final var types = scannedByModule.computeIfAbsent(moduleId, k -> new ArrayList<>());
        final var injections = injectionsByModule.computeIfAbsent(moduleId, k -> new ArrayList<>());

        try (var paths = Files.walk(sourceRoot)) {
            final var javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        final var name = path.getFileName() != null ? path.getFileName().toString() : "";
                        return name.endsWith(".java");
                    })
                    .sorted()
                    .toList();

            for (var file : javaFiles) {
                parseFile(file, types, injections, symbols);
            }
        }
    }

    private void parseFile(Path file,
                           List<ScannedType> typesOut,
                           List<ScannedInjection> injectionsOut,
                           SymbolTable symbols) {

        try {
            final var res = parser.parse(file);
            if (!res.getProblems().isEmpty()) {
                parseWarnings++;
                final String msg = safeMsg(res.getProblems().getFirst().getMessage());
                System.err.println("WARN: parse problems in " + file + " -> " + msg);
            }
            final var cuOpt = res.getResult();
            if (cuOpt.isEmpty()) {
                return;
            }

            final var cu = cuOpt.get();
            final var pkg = cu.getPackageDeclaration().map(
                    PackageDeclaration::getNameAsString)
                    .orElse("");
            final var imports = cu.getImports();
            final var fileRel = repoRoot.relativize(file.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');

            for (var cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {

                final var fqcn = resolveFqcn(cid, pkg);

                final var isInterface = cid.isInterface();
                final var ejbLocal = isInterface && hasAnno(cid, "Local");
                final var ejbRemote = isInterface && hasAnno(cid, "Remote");

                final var ejbKind = !isInterface ? ejbKind(cid) : null;
                final var isEjbBean = ejbKind != null;

                final var implementsRaw = new ArrayList<String>(cid.getImplementedTypes().size());
                for (var t : cid.getImplementedTypes()) {
                    implementsRaw.add(t.getNameAsString());
                }

                final var extendsRaw = new ArrayList<String>(cid.getExtendedTypes().size());
                for (var t : cid.getExtendedTypes()) {
                    extendsRaw.add(t.getNameAsString());
                }

                final var injectedFields = new ArrayList<InjectedField>();

                // --- Field injection: @EJB / @Inject / @PersistenceContext
                for (var fd : cid.getFields()) {
                    final var isEjb = hasAnno(fd, "EJB");
                    final var isCdi = hasAnno(fd, "Inject");
                    final var isPc = hasAnno(fd, "PersistenceContext");
                    if (!isEjb && !isCdi && !isPc) {
                        continue;
                    }
                    final var via = isEjb ? "EJB" : (isCdi ? "CDI" : "JPA");

                    var typeRaw = Ids.normalizeTypeName(fd.getElementType().toString());
                    typeRaw = resolveImportedType(typeRaw, imports);

                    for (var v : fd.getVariables()) {
                        final var fieldName = v.getNameAsString();
                        injectedFields.add(new InjectedField(fieldName, typeRaw, via));

                        injectionsOut.add(new ScannedInjection(
                                fqcn, pkg,
                                "field", fieldName,
                                typeRaw, via
                        ));
                    }
                }

                // --- Method injection (setter injection):
                // method annotated with @EJB/@Inject/@PersistenceContext; record per parameter
                for (var md : cid.getMethods()) {
                    final var isEjb = hasAnno(md, "EJB");
                    final var isCdi = hasAnno(md, "Inject");
                    final var isPc = hasAnno(md, "PersistenceContext");
                    if (!isEjb && !isCdi && !isPc) {
                        continue;
                    }
                    final var via = isEjb ? "EJB" : (isCdi ? "CDI" : "JPA");

                    final var params = md.getParameters();
                    if (params.isEmpty()) {
                        // nothing to inject; skip
                        continue;
                    }

                    // We keep it simple: one edge per parameter (setter usually has 1 param)
                    for (int i = 0; i < params.size(); i++) {
                        final var p = params.get(i);
                        var typeRaw = Ids.normalizeTypeName(p.getType().toString());
                        typeRaw = resolveImportedType(typeRaw, imports);
                        final var sig = methodSignature(md.getNameAsString(), params);

                        injectionsOut.add(new ScannedInjection(
                                fqcn, pkg,
                                "method", sig,
                                typeRaw, via
                        ));
                    }
                }

                final var st = new ScannedType(
                        fqcn,
                        pkg,
                        fileRel,
                        isInterface,
                        isEjbBean,
                        ejbKind,
                        ejbLocal,
                        ejbRemote,
                        implementsRaw,
                        extendsRaw,
                        injectedFields
                );

                typesOut.add(st);
                symbols.registerType(fqcn);
            }

        } catch (Exception ex) {
            parseWarnings++;
            System.err.println("WARN: failed to parse " + file + " -> "
                    + ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage()));
        }
    }

    private static String resolveFqcn(ClassOrInterfaceDeclaration cid, String pkg) {
        final var direct = cid.getFullyQualifiedName();
        if (direct.isPresent()) {
            return direct.get();
        }
        final String nested = nestedTypeName(cid);
        if (pkg == null || pkg.isEmpty()) {
            return nested;
        }
        return pkg + "." + nested;
    }

    private static String nestedTypeName(ClassOrInterfaceDeclaration cid) {
        final List<String> parts = new ArrayList<>();
        parts.add(cid.getNameAsString());
        var parent = cid.getParentNode().orElse(null);
        while (parent instanceof TypeDeclaration<?> td) {
            parts.add(td.getNameAsString());
            parent = td.getParentNode().orElse(null);
        }
        Collections.reverse(parts);
        return String.join(".", parts);
    }

    private static String methodSignature(String name, NodeList<Parameter> params) {
        // Compact, stable-ish string for JSONL. Not meant for compilation, just identification.
        final var sb = new StringBuilder();
        sb.append(name).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Ids.normalizeTypeName(params.get(i).getType().toString()));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String safeMsg(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    private static String resolveImportedType(String typeName, NodeList<ImportDeclaration> imports) {
        if (typeName == null || typeName.isBlank()) {
            return typeName;
        }
        if (typeName.indexOf('.') >= 0) {
            return typeName;
        }
        if (isPrimitive(typeName)) {
            return typeName;
        }

        for (var imp : imports) {
            if (imp.isStatic() || imp.isAsterisk()) {
                continue;
            }
            final String imported = imp.getNameAsString();
            final String simple = Ids.simpleNameOfFqcn(imported);
            if (simple.equals(typeName)) {
                return imported;
            }
        }
        return typeName;
    }

    private static boolean isPrimitive(String typeName) {
        return switch (typeName) {
            case "boolean", "byte", "short", "int", "long", "char", "float", "double", "void" -> true;
            default -> false;
        };
    }

    private static boolean hasAnno(NodeWithAnnotations<?> n, String simpleName) {
        for (AnnotationExpr a : n.getAnnotations()) {
            if (simpleName.equals(annoSimpleName(a))) {
                return true;
            }
        }
        return false;
    }

    private static String ejbKind(ClassOrInterfaceDeclaration cid) {
        if (hasAnno(cid, "Stateless")) {
            return "Stateless";
        }
        if (hasAnno(cid, "Stateful")) {
            return "Stateful";
        }
        if (hasAnno(cid, "Singleton")) {
            return "Singleton";
        }
        return null;
    }

    private static String annoSimpleName(AnnotationExpr a) {
        final var n = a.getNameAsString();
        final var lastDot = n.lastIndexOf('.');
        return lastDot >= 0 ? n.substring(lastDot + 1) : n;
    }

    public record InjectedField(String fieldName, String fieldTypeRaw, String via) {
    }

    public record ScannedInjection(
            String fromFqcn,
            String fromPackageName,
            String memberKind,   // field | method
            String member,       // fieldName | methodSignature
            String injectedTypeRaw,
            String via           // EJB | CDI | JPA
    ) {
    }

    public record ScannedType(
            String fqcn,
            String packageName,
            String fileRel,
            boolean isInterface,
            boolean isEjbBean,
            String ejbKind,
            boolean isEjbLocal,
            boolean isEjbRemote,
            List<String> implementsRaw,
            List<String> extendsRaw,
            List<InjectedField> injectedFields
    ) {
        public String ejbKindLower() {
            return ejbKind == null ? null : ejbKind.toLowerCase(Locale.ROOT);
        }
    }

    public int parseWarningCount() {
        return parseWarnings;
    }
}
