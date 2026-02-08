package ai.indexer.model;

/**
 * JSONL line for inject.<module>.jsonl
 * <p>
 * memberKind:
 * - "field"  for field injection
 * - "method" for setter/initializer injection
 * <p>
 * via:
 * - "EJB" for @EJB
 * - "CDI" for @Inject
 * - "JPA" for @PersistenceContext
 */
public record InjectLine(
        String from,        // t:<fqcn>
        String memberKind,  // "field" | "method"
        String member,      // fieldName OR methodSignature-ish (e.g. setX(Type))
        String type,        // t:<fqcn> (or t:<simple> if unresolved)
        String via          // "EJB" | "CDI" | "JPA"
) {
}
