package ai.indexer.model;

import java.util.List;

/**
 * JSONL line for ejb.<module>.jsonl
 */
public record EjbBindingLine(
        String iface,     // t:<fqcn>
        boolean local,
        boolean remote,
        List<String> impls // list of t:<fqcn> beans
) {
}