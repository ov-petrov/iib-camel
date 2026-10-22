# iib-camel testing strategy

> 🇷🇺 Русская версия: [test-strategy.ru.md](test-strategy.ru.md)

> Functional requirements: [functional-requirements.en.md](functional-requirements.en.md)

## 1. Purpose and principles

Testing must prove that:

1. The **converter** produces semantically equivalent artifacts: the generated Camel route "does the
   same thing" as the original IIB flow;
2. The **runtime service** routes messages to the same destinations with the same result as the
   original IIB flow.

Principles:

- **Compare behavior, not text.** "Identical XML" means semantic equivalence (the graph of route
  steps), not character-by-character equality: formatting, nesting depth and file layout do not matter.
- **Corpus lives in the repository.** Every new converter capability must be added together with
  a fixture (sample IIB artifacts) + golden files + an e2e dataset.
- **Tests do not require an installed IIB/ACE.** Test artifacts are plain text files.

## 2. Test corpus (fixtures)

The repository contains a corpus of sample IIB projects (`converter/src/test/resources/corpus/`),
expected results (goldens) and e2e datasets:

```
converter/src/test/resources/corpus/
├── corr-01-simple-adapter/          # "simple adapter" example (L1)
│   ├── Adapter.msgflow              # source flow
│   ├── Adapter.esql                 # Compute/Router logic
│   ├── expected/
│   │   ├── Adapter-route.xml        # golden: expected Camel route (canonical)
│   │   └── e2e/
│   │       ├── dataset.yml          # input messages + expected queues/fields
│   │       └── messages/*.xml       # test input messages
├── corr-02-subflows/                # subflows, direct/inline + honest statuses (L2) — Should
├── corr-03-esql-mapping/            # ESQL functions, CAST, IF/CASE (L2) — Should
└── corr-04-exotic/                  # rare nodes/constructs (L3) — Could
```

### Sample fixture: "simple adapter" (level L1, Must)

A flow that modifies a message and routes it by branches:

```
MQInput(ADAPTER.IN) → Compute (copies the body, stamps MQMD.ApplicationIdData)
                   → Route (`filterPattern: $Body/Adapter/Order/status='HIGH'`)
                       output: Match   → MQOutput(PRIORITY.Q)
                       output: Default → MQOutput(DEFAULT.Q)
                       catch/fail      → MQOutput(ERROR.Q)
```

In the repository it is kept as real `.msgflow` (XML) and `.esql` files, for example:

```xml
<!-- Adapter.msgflow (fragment): the format is Eclipse EMF/XMI, as in real IIB files -->
<nodes xmi:type="ComIbmMQInput.msgnode:FCMComposite_1" xmi:id="Input"
       queueName="ADAPTER.IN" messageDomainProperty="XMLNSC"/>
<nodes xmi:type="ComIbmCompute.msgnode:FCMComposite_1" xmi:id="Enrich"
       computeExpression="esql://routine/adapter#Adapter_Compute1.Main"/>
<nodes xmi:type="ComIbmRoute.msgnode:FCMComposite_1" xmi:id="RouteByStatus">
  <filterTable filterPattern="$Body/Adapter/Order/status='HIGH'" routingOutputTerminal="Match"/>
</nodes>
```

```sql
-- Adapter_Compute1.esql (fragment)
CREATE COMPUTE MODULE Adapter_Compute1
  CREATE FUNCTION Main() RETURNS BOOLEAN
  BEGIN
    SET OutputRoot = InputRoot;
    SET OutputRoot.MQMD.ApplicationIdData = 'IIB-CAMEL-MIG';
    RETURN TRUE;
  END;
END MODULE;
```

## 3. Testing levels

### 3.1. Level 1 — unit tests of converter components (Must)

Internal units are tested: parsers, translators, generators.

- **msgflow/subflow parser → IR (FR-1.4/1.5).** Parse the fixture → compare the IR with the expected
  model: number of nodes, terminals, connections, properties (flow topology).
- **ESQL parser (FR-3.1).** Table-driven tests: pairs "ESQL fragment → expected AST/structure".
- **Expression translator (FR-3.2).** Table-driven tests "ESQL expression → expected simple/xpath",
  including a function correspondence table (`CAST`, `SUBSTRING`, `CONCAT`, `UPPER`…).
- **XML/Java/MapStruct generators.** Verify output fragments structure and declarations on short
  fixtures producing short fragments.
- **ESQL inventory of the `analyze` command (EsqlInventoryRenderer, 2026-10).** Table-driven tests of
  the as-is verdicts: module within the subset → "fully translatable", module with branching or a
  function other than Main → "partially translatable (N statements translated)", no `Main`/FILTER →
  "out of subset", parse error → honest row plus accounting in the exit code; determinism (same
  input → byte-identical document, D-3). CLI contract (`AnalyzeCommandTest`): `esql-inventory.md`
  next to diagrams, esql-only directory → exit `0`, broken ESQL → exit `2`.

### 3.2. Level 2 — golden tests: "XML is converted identically" (Must)

For each fixture, the converter generates artifacts which are compared against **golden files** —
references stored in the corpus.

**How we compare (not raw diff):**

1. **Canonization.** XML is normalized (uniform formatting, attribute sorting, removal of positional
   noise/coordinates) and only then text-compared.
2. **Route graph.** For order-sensitive fragments a graph is built: vertices are DSL nodes
   (`from`, `choice`, `when`, `bean`, `to`…), edges — sequencing and branching,
   attributes — type+arguments. Graph comparison is the semantic equivalence.
3. **Expression comparison.** Predicate translation is verified behaviorally: on a test dataset both
   the reference ESQL predicate (a mini-interpreter covering the core functions) and the generated
   `simple` expression are evaluated — results must match.

**Fail mode and golden updates:**

- first appearance of a fixture — generation without a golden: the test is in status
  "golden not approved";
- the golden is approved explicitly (`--approve-golden` or a regeneration flag in a Maven profile)
  and goes to review: a human looks at the diff;
- afterwards any **unintentional** deviation from the golden is a red build;
- if the change is intentional — the golden is updated in the PR and reviewed again.

### 3.3. Level 3 — e2e tests of the runtime service routing: "routes where it must" (Should)

Goal: execute the generated route **in the real runtime** and verify behavior.

**Mechanics:**

- the runtime starts in a test context (`@CamelSpringBootTest`, camel-test-spring-junit5);
- instead of IBM MQ, an **embedded broker** (ActiveMQ Artemis) is used: since the `camel-jms`
  component is generic, routes with MQInput/MQOutput work against Artemis too — configuration is
  mapped through test settings;
- messages are sent to the input queue by test components; results are consumed from the output
  queues.

**Declarative dataset (described in `e2e/dataset.yml`, one dataset per corpus directory):**

```yaml
queues:                                     # queue catalog of the corpus (run isolation)
  - ADAPTER.IN
cases:
  - name: high-status-order-routed-to-priority-queue
    input:
      queue: ADAPTER.IN
      message: messages/order-high.xml
      mqmd:
        priority: 8
    expect:
      queue: ADAPTER.PRIORITY.Q                 # target queue
      mqmd:
        priority: 8
        applicationIdData: IIB-CAMEL-MIG        # Compute modification
  - name: regular-order-routed-to-default-queue
    input:  { queue: ADAPTER.IN, message: messages/order-low.xml }
    expect: { queue: ADAPTER.DEFAULT.Q }
  - name: malformed-message-goes-to-error-queue
    input:  { queue: ADAPTER.IN, message: messages/order-broken.xml }
    expect: { queue: ADAPTER.ERROR.Q }          # catch/fail branch
```

Each case: send the input message → await/read from the expected queue → compare MQMD fields and
body fields. "Routes where it must" = landing in the right queue with the right modifications and
**absence** of the message in the wrong queues.

The E2E test is parameterized over corpus directories (`E2eDatasetTest`, corr-01 and corr-02):
corr-02 additionally verifies the bean generated from ESQL (`CheckCompute1`: JMS property
`CommentField=CHECKED-BY-SUBFLOW`) and priority preservation through the direct:-inlined subflow.

**Additional level-3 techniques:**

- **AdviceWith/mock endpoints** (`camel mock:`) for scenarios where no broker is needed: quickly
  verify step order and results of `bean`/`process` invocations;
- **Mapper tests (FR-4).** The generated MapStruct mapper: input object → expected output object
  comparison, also driven by the declarative dataset.

### 3.4. Additionally: acceptance scenarios (Could)

Standalone e2e scenarios of "critical flows" covering the assembled deployment variant
(jar + real external connection configuration) — executed rarely/on release.

## 4. CI and regression

| Trigger | What runs | Duration |
|---------|-----------|----------|
| Every branch/PR (mandatory) | L1 unit + golden (corpus L1) | minutes |
| Nightly | Full corpus (L2/L3) + e2e on the embedded broker | tens of minutes |
| On release | acceptance scenarios (§3.4) + corpus rebuild | rare |

Automatic CI checks:

- the converter runs over the whole corpus; the migration report must not contain *not supported*
  statuses for **Must-nodes** of the baseline correspondence table (FR-2.2), otherwise the build is red;
- the error class "generated XML did not load into Camel" is caught by e2e (fail-fast, FR-R2.3).

## 5. Feature completion criteria (Definition of Done)

A converter capability is considered done (per FR) when:

1. a fixture covering the capability has been added to the corpus;
2. the golden is approved and passes;
3. for "routing" capabilities there is an e2e scenario with a dataset (for the Should level);
4. the migration report lines for the fixture show the expected statuses;
5. no regressions across the rest of the corpus.

## 6. Priorities (MoSCoW)

| Priority | Items |
|----------|-------|
| **Must** | corpus L1 ("simple adapter"), unit tests (IR parser, translation tables), golden tests with semantic comparison (canonization/graph), CI check "no unsupported Must-nodes" |
| **Should** | corpus L2 (ESQL/subflows), e2e on the embedded broker with a dataset, mapper tests, AdviceWith mocks |
| **Could** | corpus L3, golden auto-approval with diff review, extra metrics (node coverage), extended acceptance scenarios |
| **Won't** | full IIB/ESQL emulator (the mini-interpreter covers only core functions), comparison against a real ACE runtime in CI (possible manually, outside CI), property-based testing |

## 7. Relation to functional requirements

| Testing area | Verified FRs |
|--------------|--------------|
| Parser (msgflow/subflow/esql) | FR-1.1, FR-1.3–1.5, FR-3.1 |
| Route generation | FR-2.1–2.7 |
| ESQL translation | FR-3.1–3.5 |
| MapStruct | FR-4.1–4.4 |
| Runtime service | FR-R2.1–R2.3, FR-R3.1, FR-R4.1, FR-R5.1 |
| Report | FR-5.1, FR-5.3 |
