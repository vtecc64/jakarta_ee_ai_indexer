package ai.indexer.graph;

import java.util.List;
import java.util.Map;

import ai.indexer.model.EjbBindingLine;
import ai.indexer.model.InjectLine;
import ai.indexer.model.TypeLine;

/**
 * Fully built graph, ready for writing.
 * - Per-module JSONL lines
 * - Global indices: typeId -> moduleId, ejbIfaceId -> moduleId
 */
public record Graph(
        Map<String, ModuleFiles> modules,
        Map<String, String> typeIndex,
        Map<String, String> ejbIndex,
        int parseWarnings
) {
    public record ModuleFiles(
            List<TypeLine> types,
            List<InjectLine> inject,
            List<EjbBindingLine> ejb
    ) {
    }
}
