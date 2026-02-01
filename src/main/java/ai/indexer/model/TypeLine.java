package ai.indexer.model;

import java.util.List;

/**
 * JSONL line for types.<module>.jsonl
 */
public record TypeLine(
        String id,             // t:<fqcn>
        String kind,           // "class" | "interface"
        String file,           // repo-relative
        List<String> implementsIds, // t:<fqcn> where resolvable, else t:<simple>
        List<String> extendsIds,    // same
        String ejb,            // "stateless" | "stateful" | "singleton" | null
        List<String> ejbLocal, // list of t:<fqcn> iface IDs
        List<String> ejbRemote,// list of t:<fqcn> iface IDs
        List<String> injects,  // list of f:<fqcn>#<field> (field-only)
        List<String> injectMembers // list of f:<fqcn>#<field> and m:<fqcn>#<methodSig>
) {
}
