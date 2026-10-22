# Design: message flow diagram renderer (intermediate model → Mermaid)

> 🇷🇺 Russian version: [diagram-renderer-design.ru.md](diagram-renderer-design.ru.md)
>
> Functional requirements: [functional-requirements.en.md](functional-requirements.en.md)
> Testing strategy: [test-strategy.en.md](test-strategy.en.md)

## 1. Purpose and scope

The tool builds Mermaid text diagrams from IIB flows (`.msgflow`, `.subflow`) to:

- accelerate migration reviews — visual comparison of «source flow ↔ generated route»;
- keep diagrams in the repository next to the corpus golden files (GitHub renders `.mmd`
  inside Markdown automatically);
- illustrate documentation (`docs/diagrams/*.mmd` or mermaid blocks in READMEs, embedded manually).

**Out of scope:** editing flows, interactive editor (that is FR-7.3, Could, web UI), rendering
ESQL logic inside nodes, a pixel-perfect Toolkit replica (coordinates, IBM colors). The goal is a
structural diagram: nodes, node types, connections, terminals.

## 2. Key architectural decision (D-1)

**The renderer consumes the intermediate representation (IR); it does not parse `.msgflow` itself.**

FR-1.4 already mandates the IR carrying flow topology. Building a second parser for a
«visualization tool» would mean:

1. duplicated parsing code and diagnostics (FR-1.3);
2. a risk of «two versions of truth» about the flow structure: the converter and the diagram
   could disagree;
3. a lost opportunity: the diagram is a free smoke test of IR correctness — if the renderer draws
   the flow correctly, the parser extracted the topology correctly, and so did the converter.

Consequence: this module owns only «IR → Mermaid» plus the CLI wiring. The shared IR parser
(and its extensions for ESQL/schemas) belongs to the main conversion effort (see section 8
on coordination).

## 3. Intermediate model (IR): minimal topology slice

> **Status (post-M1).** The IR model is owned by the parallel main-conversion track
> (`converter.msgflow`: public records `MsgFlow` / `FlowNode` / `FlowConnection`, parser `MsgFlowParser`).
> The diagram renderer consumes it directly; entities from the sketch below (`SourceRef`, `NodeCategory`)
> live as renderer-internal details (`converter.diagram`). If the contract diverges, adjust this document
> and coordinate with the IR owner — no silent changes.

Package `com.example.iib_camel.converter.ir` (later extended with ESQL/schema types):

```java
package com.example.iib_camel.converter.ir;

/** A message flow or subflow after parsing a .msgflow/.subflow file. */
public interface MsgFlow {
    String name();                       // file name without extension
    SourceRef source();                  // origin: file, position (FR-5.2, FR-1.3)
    List<FlowNode> nodes();              // declaration order (stable!)
    List<FlowConnection> connections();
    Set<SourceRef> unresolvedRefs();     // unresolved .subflow references (FR-1.5)
}

/** A flow node. */
public interface FlowNode {
    String id();                         // node id from the file (not normalized — traceability)
    String name();                       // display name
    String type();                       // node type as declared («MQInput», «Compute», …)
    NodeCategory category();             // styling category (see table below)
    Map<String, String> properties();    // recognized properties (queues, module name, …)
    boolean subflowRef();                // true for a subflow reference
    Optional<SourceRef> subflowTarget(); // what it references
}

/** Terminal → terminal connection. */
public interface FlowConnection {
    String sourceNodeId();
    String sourceTerminal();             // empty when not specified in the file
    String targetNodeId();
    String targetTerminal();
}

/** Source reference (file + line) for diagnostics and reports. */
public interface SourceRef {
    Path file();                         // relative to the input root
    int line();                          // 1-based; -1 when unknown
}

/** Node category used for diagram styling. */
public enum NodeCategory { INPUT, OUTPUT, PROCESS, ROUTE, ERROR, REFERENCE, OTHER }
```

Stability rules (driven by golden tests):

- **D-3:** `nodes()` preserves declaration order; diagram node ids (`N1`, `N2`, …) are assigned
  by that order and never derived from names or hash codes. Renaming a node changes only its
  label in the diagram diff.
- **D-4:** no nondeterminism in rendering: no system time, no random ids, iteration only over
  ordered collections.

## 4. Mermaid rendering

### 4.1. Output contract

```
flowchart LR                      // LR by default; TB via --orientation TB
    N1["MQInput1"]:::input
    N2["Compute1"]:::process
    N3[["CheckStatus (subflow)"]]:::reference
    ...
    N1 --> N2
    N1 -. catch .-> N4             // non-primary-terminal edges: dashed with a label
    ...
    classDef input fill:#e8eaf6,stroke:#3949ab,stroke-width:1px
    classDef output fill:#e8f5e9,stroke:#2e7d32,stroke-width:1px
    ...
```

Output rules:

- **D-5 (terminals):** an edge is labeled and dashed when its source terminal is unknown or not
  in the «primary outputs» list of the source node. Currently the primary output is `out`
  (plus `match*` for `Router`). The list is refined after real files arrive (assumption A3) —
  deliberately simple; exact IIB terminal semantics is not the goal here.
- **D-6 (labels):** node label is the node name, with the node type on the next line (`<br/>`).
  Extra data (e.g. queue names for INPUT) is *not* shown yet — pending knowledge of which
  properties real files actually carry (assumption A5).
- **D-7 (layout):** a single `flowchart`; flows with >30 nodes may degrade visually — the
  `--orientation TB` flag and per-subgraph splitting are decided after real files arrive.

### 4.2. Node category registry (initial, extensible)

| Category | IIB nodes (initial list) |
|----------|--------------------------|
| INPUT | MQInput, HTTPInput, JMSInput, FileInput, SOAPInput, TCPInput, MQGet |
| OUTPUT | MQOutput, HTTPReply, JMSOutput, FileOutput, SOAPReply, TCPOutput, MQReply |
| ROUTE | Filter, Route, RouteToLabel, Label, Router |
| PROCESS | Compute, JavaCompute, Mapping, .NETCompute, Database, Collector, Aggregate, Resequence, Split, TimeoutControl, TimeoutNotify, SetVariable, ResetContentDescriptor, PASSTHRU, Trace |
| ERROR | Throw, TryCatch |
| REFERENCE | subflow references (`.subflow`) |
| OTHER | any unknown type (fallback, styled with a warning) |

The registry is a plain `Map<String, NodeCategory>` (key — `type()` as written in the file,
case-insensitive) with the `OTHER` fallback; extended as real files arrive. No heuristic guessing.

### 4.3. Escaping (`MermaidEscaper`)

Label substitutions (composable): `"` → `&quot;`, `#` → `&num;`, `|` → `&#124;`, `<` → `&lt;`,
`>` → `&gt;`, line breaks → space. Diagram node ids (`N1`, …) are generated by us, so no escaping
is needed there. Covered by a unit-test table.

### 4.4. Secondary output formats: AsciiDoc and Markdown report (D-8, revised 2026-10)

> **Status:** the historical `--format ascii` («outline tree») is removed — it duplicated what
> Mermaid shows better and cluttered the output. The `AsciiDiagramRenderer` class and the
> corresponding CLI option are gone.

A second format `--format adoc` produces an AsciiDoc document (`.adoc`): document title
(`= flow name` from the IR name), a `:source-file:` attribute, a reference to the graphic
diagram (`.mmd` next to it) and node/connection tables. Purpose: user documentation managed
through Asciidoctor.

A third format `--format md` is a «before migration» inventory report (Markdown, `.md`,
renderer `AnalysisMarkdownRenderer`): an embedded ```mermaid``` block (GitHub/IDEA render it),
node tables (node, type, IIB category, key property, whether a conversion rule exists — checked
against `RouteGenerator.SUPPORTED_BASE_KINDS`) and connection tables, plus a final
«Migration readiness» section answering the key pre-migration question: is handling provided
for all IIB nodes/entities, and which types need manual migration (FR-2.7) or rule extension
(FR-2.2, Should). Report wording is plain business language: no internal jargon
(«outline tree» etc.) in user-facing artifacts.

Mermaid (`--format mermaid`) stays canonical for GitHub; the default is `--format all` —
all three formats at once. Determinism rules D-3/D-4 apply to all formats; an HTML viewer is
treated as a one-off review artifact, not part of the contract.

## 5. CLI (`analyze` command)

The main class is built on picocli (the only external dependency of the module at this stage):

```
java -jar converter.jar analyze [--input <file|dir>] [--output <dir>]
                                [--orientation LR|TB] [--format mermaid|adoc|md|all] [--fail-fast]
```

- `--input` — a `.msgflow`/`.subflow` file or a directory (recursive walk, only these extensions);
  default `./input` relative to the current directory. Satisfies FR-1.2 (batch processing)
  by construction.
- `--output` — output directory, default `./output` (created if missing); each artifact →
  `<name>.mmd`, `<name>.adoc` or `<name>.md` (per `--format`, see 4.4).
- `--format` — output format: `all` (default — all three), `mermaid`, `adoc` or `md`
  (inventory report, D-8).
- Diagnostics (FR-1.3): `path:line: message` on stderr; summary on stdout
  (files, nodes, edges, warnings).
- **Exit codes:** `0` — everything rendered; `1` — invalid arguments; `2` — partial success
  (some files failed to parse, the rest rendered); `3` — nothing rendered (fatal).

## 6. Requirements to the IR parser (contract for the parser author)

1. Namespace-agnostic (D-2): element types and structure are matched by **local names**, not by
   namespace URIs — IIB versions change URIs while the format essence stays stable.
2. Every IR entity carries a `SourceRef(file, line)` — FR-1.3 diagnostics starts at the parser.
3. Lenient mode: unknown nodes/attributes and structural surprises do not abort parsing — they
   accumulate as warnings (the FR-2.7 spirit, extensibility FR-8.2).
4. Unresolvable `.subflow` references are recorded in `unresolvedRefs`; the renderer draws a
   placeholder node.

## 7. Testing (aligned with the [testing strategy](test-strategy.en.md))

- **Unit level:** escaping (substitution table); renderer on synthetic IR — labels, edges,
  terminal captions, classDef ordering, registry-driven categorization.
- **Golden level:** for each corpus fixture (`converter/src/test/resources/corpus/corr-0X-*`) the
  renderer produces `expected/<flow-name>.mmd` next to the golden routes. Golden approval follows
  the strategy: first run → «golden not approved», explicit approval via the regeneration flag,
  then any unintentional deviation breaks the build.
- **Determinism:** the same input twice → byte-identical output (unit test).
- **Assumption audit:** assumptions A1–A8 (section 8) get statuses after the neighboring session
  delivers real files; parser/category/terminal rules are then adjusted exactly where needed.

## 8. Format assumptions (A1–A8) — to be verified against real files

The neighboring session adds real `.msgflow`/`.subflow` files (placement agreement — see 8.1).
Until then the parser relies on general knowledge of the format; each assumption must be settled
with facts from real files so that only affected parts change:

| # | Assumption | How to verify | Risk |
|---|-----------|---------------|------|
| A1 | Root element is a flow element (`flow`-family); namespaces vary across MB/IIB/ACE versions | inspect the root and ns URI of real files; parser is namespace-agnostic — low risk | low |
| A2 | Nodes live in a dedicated container (`nodes`-family); node type comes from the element name or a `type` attribute | container structure in a real file | medium |
| A3 | Connections are a flat list of elements under `connections` with source/target terminal attributes | exact terminal attribute names | medium |
| A4 | A subflow lives in its own file; the parent references it via a node pointing to the `.subflow` | `.subflow` file format, reference node shape | medium |
| A5 | Node properties are sub-elements/attributes (queue for INPUT, MQ host, ESQL module); which of them are actually populated | see which properties are worth rendering under labels | low |
| A6 | The file carries Toolkit layout coordinates (node positions) | verify that ignoring them is safe | low |
| A7 | Version differences (MB9 / IIB10 / ACE11+) — ns URIs and minor fields; UTF-8 encoding (BOM possible) | compare files of different versions if available | low |
| A8 | Non-standard / user-defined node types occur | OTHER fallback + warning covers it | low |

### 8.1. Agreement on fixture placement (neighboring session)

Files go into the corpus per the [testing strategy](test-strategy.en.md):
`converter/src/test/resources/corpus/corr-0X-<name>/<FlowName>.msgflow` (with `.esql`, `.subflow`
nearby). That allows goldens in `expected/` immediately and a one-command assumption audit.
Along with the files, a one-line note is welcome: IIB/ACE version, presence of subflows, whether
names were sanitized (see risk R-2).

## 9. Milestones

- **M0 — this document** (done).
- **M1** ✅ — `MermaidDiagramRenderer` on top of the main-conversion IR (`converter.msgflow`),
  secondary formats: ASCII outline `AsciiDiagramRenderer` and AsciiDoc document
  `AsciiDocDiagramRenderer` (D-8, added by review decisions), CLI `diagram` command with
  `--format mermaid|ascii|adoc` (picocli), unit tests on synthetic data, executable jar
  (`Manifest + lib/` via jar-plugin + dependency-plugin). The real `corr-01-simple-adapter`
  fixture was rendered in both text formats by smoke runs — contracts verified (A1–A3, A5, A6).
  *2026-10 retrospective:* the ASCII outline is removed (it duplicated Mermaid), the command is
  renamed `diagram` → `analyze`, a «before migration» Markdown report is added
  (`AnalysisMarkdownRenderer`, D-8 revised — see 4.4); defaults `--input ./input` →
  `--output ./output`, `--format all`.
- **M2** — real assumption audit A1–A8 over the whole corpus from the neighboring session →
  parser/category adjustments, golden approval over the corpus.
- **M3** — batch generation of `docs/diagrams/` for README examples (optionally: SVG via
  mermaid-cli; embedding in READMEs is not Must).

## 10. Risks

| Risk | Mitigation |
|------|------------|
| R-1: Mermaid readability on 30+ node flows | `--orientation TB`; later — per-input-node diagram splitting (after real files, D-7) |
| R-2: confidentiality of business names in corpus and diagrams | corpus fixtures are sanitized copies; diagrams are built from the corpus, not from production files |
| R-3: divergence from the main converter parser during parallel development | D-1: one parser, renderer consumes IR; IR extensions are new types, not contract changes |
| R-4: Mermaid versions (GitHub/mermaid-cli) render exotic syntax differently | only basic `flowchart` features are used; verified on GitHub and mmdc in M3 |
