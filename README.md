# Jakarta EE Monorepo AI Indexer

Lightweight Java 21 source indexer for Jakarta EE codebases. It scans modules,
EJB/CDI/JPA injections, and type relationships to produce query-friendly JSONL
files plus compact indices. The primary use case is LLMs and AI agents: the
output is designed to reduce analysis time and token usage during code review
and architecture exploration.

This is an MVP and intentionally minimal: it focuses on static source parsing,
does not attempt full symbol resolution, and may miss edge cases. It is meant
for architecture analysis and tooling integrations, not as a compiler-grade
indexer.

It does not work standalone yet!

## Features
- Module-aware scanning of Java sources
- EJB interface bindings (local/remote) and implementations
- Injection graph for field and method injections
- Type hierarchy and file location indexing
- Deterministic, diff-friendly outputs
- LLM-friendly JSONL + indices to minimize context loading

## Usage
From the repo root:

```bash
./gradlew :ai-indexer:run
```

Optional arguments:

```bash
./gradlew :ai-indexer:run --args="--outDir=.repo-ai --includeTests=false --modules=module1,module2"
```

## Output
Default output directory: `.repo-ai`

- `types.<module>.jsonl` (types, hierarchy, injections)
- `inject.<module>.jsonl` (injection edges)
- `ejb.<module>.jsonl` (EJB interface bindings)
- `types.index.json`, `ejb.index.json` (global indices)
- `index.json` (master index + summary)

## Limitations
- Best-effort parsing; no bytecode analysis
- Simple type resolution (imports + unique simple names)
- Nested classes are included but resolved names may vary by parser
