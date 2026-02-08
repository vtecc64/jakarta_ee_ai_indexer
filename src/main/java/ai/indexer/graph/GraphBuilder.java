package ai.indexer.graph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ai.indexer.model.EjbBindingLine;
import ai.indexer.model.Ids;
import ai.indexer.model.InjectLine;
import ai.indexer.model.TypeLine;
import ai.indexer.modules.ModuleLayout;
import ai.indexer.scan.SourceRootFinder;
import ai.indexer.scan.TypeScanner;

/**
 * Builds module-split JSONL data + global indices.
 * No hashing/incremental logic (by request). Designed to allow future extensions.
 */
public final class GraphBuilder {

    private final Path repoRoot;
    private final ModuleLayout layout;
    private final boolean includeTests;

    public GraphBuilder(Path repoRoot, ModuleLayout layout, boolean includeTests) {
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.includeTests = includeTests;
    }

    public Graph build() throws Exception {
        final var moduleDirs = layout.moduleDirsById();

        // Step 1: find java source roots under known modules (and optionally unknown)
        final SourceRootFinder rootFinder = new SourceRootFinder(moduleDirs, includeTests);
        final var sourceRoots = rootFinder.findAllSourceRoots(); // Map<moduleId, List<Path>>

        // Step 2: scan types (first pass) to build symbol table
        final SymbolTable symbols = new SymbolTable();
        final TypeScanner scanner = new TypeScanner(repoRoot);

        // per-module raw results
        final Map<String, List<TypeScanner.ScannedType>> scannedByModule = new HashMap<>();
        final Map<String, List<TypeScanner.ScannedInjection>> injectionsByModule = new HashMap<>();

        for (var e : sourceRoots.entrySet()) {
            final String moduleId = e.getKey();
            final List<Path> roots = e.getValue();

            for (Path root : roots) {
                scanner.scan(root, moduleId, scannedByModule, injectionsByModule, symbols);
            }
        }

        symbols.finalizeIndex();

        final Map<String, List<String>> injectMembersByType = new HashMap<>();
        for (var injections : injectionsByModule.values()) {
            for (var si : injections) {
                final String memberId = "field".equals(si.memberKind())
                        ? Ids.fieldId(si.fromFqcn(), si.member())
                        : Ids.methodId(si.fromFqcn(), si.member());
                injectMembersByType.computeIfAbsent(si.fromFqcn(), k -> new ArrayList<>()).add(memberId);
            }
        }

        // Step 3: convert scanned types to output lines (resolve refs lightly)
        final Map<String, Graph.ModuleFiles> moduleFiles = new HashMap<>();
        final Map<String, String> typeIndex = new HashMap<>();
        final Map<String, String> ejbIndex = new HashMap<>();

        // Precompute EJB interface flags and bean->interfaces mapping
        final Map<String, TypeScanner.ScannedType> byFqcn = new HashMap<>();
        for (var e : scannedByModule.entrySet()) {
            for (var st : e.getValue()) {
                byFqcn.put(st.fqcn(), st);
            }
        }

        // Build EJB bindings: ifaceFqcn -> impl bean fqcn list + local/remote flags
        final Map<String, EjbBindingAccumulator> ejbBindings = new HashMap<>();
        for (var e : scannedByModule.entrySet()) {
            for (var st : e.getValue()) {
                if (!st.isEjbBean()) {
                    continue;
                }
                for (String implName : st.implementsRaw()) {
                    final String ifaceFqcn = symbols.resolveToFqcnIfPossible(implName, st.packageName());
                    if (ifaceFqcn == null) {
                        continue;
                    }

                    final var ifaceType = byFqcn.get(ifaceFqcn);
                    if (ifaceType == null || !ifaceType.isInterface()) {
                        continue;
                    }
                    if (!ifaceType.isEjbLocal() && !ifaceType.isEjbRemote()) {
                        continue;
                    }

                    final var acc = ejbBindings.computeIfAbsent(
                            ifaceFqcn,
                            k -> new EjbBindingAccumulator(ifaceType.isEjbLocal(), ifaceType.isEjbRemote()));
                    acc.impls.add(st.fqcn());
                }
            }
        }

        // Per module: build JSONL lines
        final List<String> moduleIds = new ArrayList<>(scannedByModule.keySet());
        Collections.sort(moduleIds);

        for (String moduleId : moduleIds) {
            final List<TypeLine> typeLines = new ArrayList<>();
            final List<InjectLine> injectLines = new ArrayList<>();
            final List<EjbBindingLine> ejbLines = new ArrayList<>();

            final List<TypeScanner.ScannedType> types = scannedByModule.getOrDefault(moduleId, List.of());
            for (var st : types) {
                final String typeId = Ids.typeId(st.fqcn());
                typeIndex.put(typeId, moduleId);

                final List<String> implIds = new ArrayList<>(st.implementsRaw().size());
                for (String n : st.implementsRaw()) {
                    implIds.add(symbols.toTypeId(n, st.packageName()));
                }
                Collections.sort(implIds);

                final List<String> extIds = new ArrayList<>(st.extendsRaw().size());
                for (String n : st.extendsRaw()) {
                    extIds.add(symbols.toTypeId(n, st.packageName()));
                }
                Collections.sort(extIds);

                final String ejbKind = st.ejbKindLower(); // stateless/stateful/singleton or null

                // For beans: resolve which EJB interfaces are local/remote (from scanned interface annotations)
                final List<String> ejbLocal = new ArrayList<>();
                final List<String> ejbRemote = new ArrayList<>();
                if (st.isEjbBean()) {
                    for (String implName : st.implementsRaw()) {
                        final String ifaceFqcn = symbols.resolveToFqcnIfPossible(implName, st.packageName());
                        if (ifaceFqcn == null) {
                            continue;
                        }
                        final var ifaceType = byFqcn.get(ifaceFqcn);
                        if (ifaceType == null) {
                            continue;
                        }
                        if (ifaceType.isEjbLocal()) {
                            ejbLocal.add(Ids.typeId(ifaceFqcn));
                        }
                        if (ifaceType.isEjbRemote()) {
                            ejbRemote.add(Ids.typeId(ifaceFqcn));
                        }
                    }
                }
                Collections.sort(ejbLocal);
                Collections.sort(ejbRemote);

                final List<String> injectFieldIds = new ArrayList<>(st.injectedFields().size());
                for (var f : st.injectedFields()) {
                    injectFieldIds.add(Ids.fieldId(st.fqcn(), f.fieldName()));
                }
                Collections.sort(injectFieldIds);

                final List<String> injectMemberIds = new ArrayList<>(
                        injectMembersByType.getOrDefault(st.fqcn(), List.of()));
                Collections.sort(injectMemberIds);

                typeLines.add(new TypeLine(
                        typeId,
                        st.isInterface() ? "interface" : "class",
                        st.fileRel(),
                        implIds,
                        extIds,
                        ejbKind,
                        ejbLocal,
                        ejbRemote,
                        injectFieldIds,
                        injectMemberIds
                ));
            }

            // Injection edges for this module (field + method injections; EJB/CDI/JPA)
            final List<TypeScanner.ScannedInjection> inj = injectionsByModule.getOrDefault(moduleId, List.of());
            for (var si : inj) {
                final var from = Ids.typeId(si.fromFqcn());
                final var type = symbols.toTypeId(si.injectedTypeRaw(), si.fromPackageName());
                injectLines.add(new InjectLine(from, si.memberKind(), si.member(), type, si.via()));
            }

            // EJB bindings for this module: only those interfaces that belong to this module
            for (var entry : ejbBindings.entrySet()) {
                final String ifaceFqcn = entry.getKey();
                final String ifaceId = Ids.typeId(ifaceFqcn);

                // Module of interface is based on typeIndex (if known); otherwise skip
                final String ifaceModule = typeIndex.get(ifaceId);
                if (ifaceModule == null || !ifaceModule.equals(moduleId)) {
                    continue;
                }

                ejbIndex.put(ifaceId, moduleId);

                final var acc = entry.getValue();
                final List<String> implFqcns = new ArrayList<>(acc.impls);
                Collections.sort(implFqcns);
                final List<String> implIds = new ArrayList<>(implFqcns.size());
                for (String implFqcn : implFqcns) {
                    implIds.add(Ids.typeId(implFqcn));
                }

                ejbLines.add(new EjbBindingLine(ifaceId, acc.local, acc.remote, implIds));
            }

            typeLines.sort(Comparator.comparing(TypeLine::id));
            injectLines.sort(Comparator.comparing(InjectLine::from)
                    .thenComparing(InjectLine::memberKind)
                    .thenComparing(InjectLine::member)
                    .thenComparing(InjectLine::type)
                    .thenComparing(InjectLine::via));
            ejbLines.sort(Comparator.comparing(EjbBindingLine::iface));

            moduleFiles.put(moduleId, new Graph.ModuleFiles(typeLines, injectLines, ejbLines));
        }

        // Ensure modules from settings.gradle exist in output even if empty (optional)
        for (String moduleId : layout.moduleIdsSorted()) {
            moduleFiles.putIfAbsent(moduleId, new Graph.ModuleFiles(List.of(), List.of(), List.of()));
        }

        return new Graph(moduleFiles, typeIndex, ejbIndex, scanner.parseWarningCount());
    }

    private static final class EjbBindingAccumulator {
        final boolean local;
        final boolean remote;
        final List<String> impls = new ArrayList<>();

        private EjbBindingAccumulator(boolean local, boolean remote) {
            this.local = local;
            this.remote = remote;
        }
    }
}
