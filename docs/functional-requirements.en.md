# Functional requirements of the iib-camel project

> 🇷🇺 Русская версия: [functional-requirements.ru.md](functional-requirements.ru.md)

A migration tool for IBM Integration Bus (IIB/ACE) applications to Spring Boot + Apache Camel.

Every requirement is labeled with a **MoSCoW** priority (see section 5): **Must** — mandatory for the
first version, **Should** — important, deferrable, **Could** — desirable, done if resources allow,
**Won't** — deliberately out of scope for this version.

## 1. Terms and abbreviations

| Term | Definition |
|------|------------|
| **IIB** | IBM Integration Bus / IBM App Connect Enterprise (ACE) — integration platform |
| **msgflow** | Message flow description file (`.msgflow`) |
| **subflow** | Subflow description file (`.subflow`) |
| **ESQL** | Extended SQL — IIB procedural language used by Compute, Filter, Database nodes |
| **Message Set** | Set of message format descriptions (`.mset`, XSD, DFDL, JSON schemas) |
| **ULM** | Unified Logical Message of IIB: Properties, MQMD, Body, Environment, LocalEnvironment, DestinationList |
| **Intermediary Model (IR)** | Internal representation of artifacts after parsing; all generators are built on top of it |
| **Converter** | Separate one-shot Java application (module `converter`) that converts IIB artifacts into target artifacts |
| **Runtime service** | Spring Boot application (module `runtime`) that executes the generated Apache Camel routes |

## 2. Product composition and context of use

The product consists of two components:

1. **Converter** (one-shot CLI, module `converter`) — started by a command, parses IIB artifacts,
   generates Camel XML DSL, Java classes, MapStruct mappers and a migration report, then terminates.
   It leaves no background processes and does not start web services.
2. **Runtime service** (module `runtime`, Spring Boot) — executes the prepared Camel routes: this is
   the service on which the migrated integration actually runs.

The system is used:

- by integration engineers in "IIB → Spring Boot + Apache Camel" migration projects;
- in CI/CD: the converter regenerates target artifacts after source flow changes;
- as a one-shot converter for initial porting, and a scaffold generator for iterative porting.

**The system is not** an IIB runtime and does not replace manual business-logic analysis.

## 3. Functional requirements: converter

### 3.1. Input: acceptance and parsing of IIB artifacts

- **FR-1.1. (Must)** The system shall accept an IIB project directory (or its subset) and parse files of the following types:
  - `.msgflow` — message flows;
  - `.subflow` — subflows;
  - `.esql` — ESQL modules;
  - `.mset`, `.mxsd`, XSD, DFDL and JSON schemas — message sets and data models;
  - project files (`.project`, `MANIFEST.mf` — deployment manifests of libraries and applications).
- **FR-1.2. (Must)** The system shall support batch processing: conversion of all artifacts in the given directory in a single run.
- **FR-1.3. (Must)** While parsing, the system shall validate file structure and report parsing errors
  with the file, line/position and cause.
- **FR-1.4. (Must)** The system shall build an intermediary model (IR) containing:
  - flow topology: nodes, node types, terminals (in/out/catch/fail etc.), connections;
  - node properties (configurations, filters, ESQL expressions);
  - relations of flows to subflows and ESQL modules;
  - message schemas bound to nodes with indication of the format.
- **FR-1.5. (Must)** The system shall resolve cross-artifact references: subflows inside flows, ESQL modules on
  Compute/Filter/Database nodes, schemas on input/output nodes. Unresolved references are recorded as
  warnings in the report.

### 3.2. Apache Camel generation (XML DSL)

- **FR-2.1. (Must)** The system shall generate Apache Camel routes in XML DSL format (`<routes>…</routes>`)
  based on `.msgflow` flows.
- **FR-2.2. (Must/Should)** The system shall map IIB nodes to Camel elements according to a correspondence
  table. The baseline node set (input/output, Router, Filter, Throw, Validate, catch/fail handling) is
  **Must**; the extended set (Aggregate, Resequence, TimeoutControl, Database etc.) is **Should**;
  exact rules are a subject of configuration:

  | IIB node | Target Apache Camel element |
  |----------|------------------------------|
  | MQInput / HTTPInput / FileInput / JMSInput | from: `jms`, `netty-http` / `platform-http`, `file`, `camel-jms` etc. |
  | MQOutput / HTTPReply / FileOutput / JMSReply | to (sync/async variants) |
  | Router (Route) / Filter | `choice`/`when`/`otherwise`, `filter` |
  | Compute (ESQL) | processor/bean wrapper; see FR-3.x |
  | Mapping | MapStruct mapper generation and call insertion; see FR-4.x |
  | TryCatch | `doTry`/`doCatch` |
  | Throw | `throwException` |
  | Validate | `validate` (XSD/JSON) |
  | Trace | logging (`log`, `wireTap` to a guarded channel) |
  | Loop / While | `loop`, `while` |
  | Aggregate / Collect | `aggregate` with a custom aggregation strategy |
  | Resequence | `resequence` |
  | Split | `split` |
  | TimeoutControl / TimeoutNotify | `aggregate` + timer/`scheduler` |
  | SFTP/FTP (File*) | `sftp`/`ftp` |
  | Database (SQL) | `sql`/`jdbc` |
  | SetVariable / ResetContentDescriptor | exchange property/header setting (`exchangeProperty`, `setHeader`) |
  | Request subtree (Request/Reply pairs) | `to` + `setExchangePattern` (`InOut`) |
  | subflow (`.subflow`) | expansion into an embedded step of the route (`direct`/route inlining or a dedicated `<route>` called via `to`) |

  **IIB node catalog coverage (assessment, 2026-10).** The IIB/ACE 12.x palette of built-in nodes
  contains **~100+ node types** grouped into ~15 category drawers. The converter supports 4 kinds
  so far (`MQInput`, `MQOutput`, `Compute`, `Route` + catch/fail terminal semantics) — formally ~4%
  of the catalog. Breakdown by category and expansion priorities (FR-2.2 Should):

  | IBM category | Example nodes | Status | Expansion candidates (Should) |
  |---|---|---|---|
  | Construction | Input, Output, Compute, Filter, Route, Throw, TryCatch, Validate | 2 of ~10 (Compute, Route) | Filter |
  | MQ | MQInput, MQOutput, MQReply, MQGet, MQHeader, Publish | 2 of ~7 (MQInput, MQOutput) | MQReply (request/reply), MQGet |
  | JMS | JMSInput/Output/Reply/Receive | 0 of 4 | JMSInput, JMSOutput (nearly 1:1 to the MQ rules) |
  | Transformation | ResetContentDescriptor, SetVariable | 0 | ResetContentDescriptor |
  | Trace/Verify | Trace | 0 of 1 | Trace (→ `to(log)`, trivial) |
  | Timing | TimeoutNotification/Control, Timer | 0 | TimeoutNotification (→ `timer`) |
  | Collating | Collector, Resequence, Sequence | 0 | as needed |
  | Database | DatabaseInput, DatabaseRetrieve, DatabaseRoute | 0 | `sql`/`jdbc` vertical as a whole |
  | Web services | HTTPInput/Reply/Request, SOAPInput/Request/Extract, REST* | 0 | a separate transport vertical |
  | File/Email | FileInput/Output/Read, EmailOutput | 0 | FileInput, FileOutput |
  | Other (adapters) | CD*, Kafka, Salesforce, SAP, BigData, IoT, etc. | 0 | out of scope for the current stage |

  Nodes not covered by the FR-2.2 mapping table are not discarded: per FR-2.7 the converter emits
  a TODO stub and marks the node in the report (FR-5.1), so no rule may be silently lost.
- **FR-2.3. (Must)** The system shall preserve terminal wiring structure: branchings, connections to different
  outputs, catch/fail handlers — mapped to the corresponding constructs (`choice`, `doTry`,
  `onException`, `errorHandler`).
- **FR-2.4. (Should)** For each subflow included in a flow, the system shall either create a separate route
  (`<route>` with an entry point via `direct:` named after the subflow) or inline its steps into the
  parent route. The method is selected by configuration (default — `direct:` route).
- **FR-2.5. (Must)** Generated XML shall be valid and loadable into the Camel context of the runtime
  service without manual syntax fixes.
- **FR-2.6. (Should)** Every generated route/step shall contain a comment indicating the source: flow name,
  node name, line of the source file (traceability).
- **FR-2.7. (Should)** Nodes without a defined conversion rule are not discarded: the system shall mark
  such nodes in the report (FR-5) and generate a stub with a TODO comment.

### 3.3. ESQL translation

- **FR-3.1. (Should)** The system shall parse ESQL syntax: modules, functions, procedures, `DECLARE`
  statements, `SET`/`IF`/`CASE`/`ELSEIF` expressions, predicates, loops (`LOOP`, `WHILE`), handlers
  (`HANDLER`), calls (`CALL`), `BROWSE`/`PASSTHRU` (recognized at the level of detection and warnings).
- **FR-3.2. (Should)** The system shall translate:
  - ESQL expressions (arithmetic, logical, string functions `CAST`, `SUBSTRING`, `UPPER`, etc.)
    into Java/Camel expression equivalents (`simple`, `ognl`, `xpath`);
  - filter predicates (Filter nodes, Route conditions) into Camel predicates (`simple`/`jsonpath`/`xpath`);
  - `IF/ELSE` and `CASE` branching into processor constructs (`choice`) or Java code;
  - accesses to the message tree (`InputRoot`, `OutputRoot`, `Environment`, `LocalEnvironment`,
    `Properties`, `MQMD`) into Camel body/header operations or calls to intermediary ULM POJO models.
- **FR-3.3. (Should)** Expressions impossible to auto-translate shall:
  - either be translated partially, leaving a `TODO` stub comment and a reference to the source line;
  - or be marked in the report as requiring manual development.
- **FR-3.4. (Should)** The system shall generate intermediary POJO classes (ULM model: message body,
  headers, Environment/LocalEnvironment) to be used in processors and mappers — based on the analyzed
  schemas (Message Set) and tree accesses detected in ESQL.
- **FR-3.5. (Should)** ESQL fragments wholly bound to a specific Compute node shall be translated into
  dedicated classes/methods invoked from the route (`bean`, `process`), preserving the "node ↔ method"
  relation.

### 3.4. MapStruct mapper generation

- **FR-4.1. (Must/Should)** By the logic of Mapping nodes (message transformation flows), the system shall
  generate Java interfaces and implementations of **MapStruct** mappers (annotation `@Mapper`). Baseline
  rules are **Must**; complex schemes are **Should**. The rules include:
  - "field → field" mapping (Must);
  - type conversions (analog of `CAST`) (Should);
  - constant values and default values (Should);
  - expressions/computed fields (analog of Mapping `user-defined` functions) (Should);
  - conditional mappings (analog of Mapping conditions) (Should);
  - multiple sources/targets — field aggregation from several trees (Should).
- **FR-4.2. (Should)** By Compute nodes, the system shall generate mappers for typical operations
  "input object → output object" (copy/transformation of the Body structure):
  - simple copy — via auto-generation of a mapper method (source-target by field/sub-tree name);
  - complex operations — a Java class (`@Mapper` + auxiliary `@Named` methods)
    with stub comments referencing ESQL lines.
- **FR-4.3. (Must)** The system shall generate mappers marked for Spring
  (`@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)`), so they embed into the Spring
  context of the runtime service.
- **FR-4.4. (Must)** Generated mappers shall compile without manual syntax fixes and reference classes
  of the intermediary model (FR-3.4).

### 3.5. Migration report and traceability

- **FR-5.1. (Must)** After processing, the system shall produce a migration report (format — at least
  Markdown, optionally HTML/JSON), containing:
  - the list of parsed input files and artifacts;
  - each conversion unit (flow, node, ESQL statement) with a status: *converted* /
    *partially converted* / *requires manual work* / *not supported*;
  - the list of parsing issues (errors, warnings);
  - statistics by count of nodes/statements per type and status.
- **FR-5.2. (Should)** Every report record shall contain a link to the source (file, node, ESQL line)
  and, when available, to the generated file/fragment.
- **FR-5.3. (Could)** Statistics and statuses shall allow assessing the migration completeness of the
  project (e.g., % of converted nodes).

### 3.6. Validation of generated artifacts

- **FR-6.1. (Should)** The system shall validate generated Camel XML for syntax
  (compliance with the Camel Runtime XSD) and presence of all references (`direct:` points, bean identifiers).
- **FR-6.2. (Could)** Validation of generated Java/MapStruct sources for compilability
  (including running `mvn compile` in a temp directory) — when the option is enabled.

### 3.7. User interface (CLI, API)

- **FR-7.1. (Must)** The system shall provide a CLI command for running conversion with the following
  parameters (minimal set):
  - input directory/files;
  - output directory;
  - output format (routes, mappers, model, report) — all by default;
  - subflow embedding mode (`direct`/`inline`);
  - correspondence configuration file (table FR-2.2) to override rules;
  - logging levels.
- **FR-7.2. (Should)** The system shall be embeddable into a larger application
  (public Java conversion API).
- **FR-7.3. (Could)** Strategic goal: web interface (rule editor, flow ↔ route visualization).

### 3.8. Configurability and extensibility

- **FR-8.1. (Should)** Correspondence rules "IIB node → Camel element" shall be configurable
  (internal default set + externally overridable table).
- **FR-8.2. (Could)** An extension point (plugins/SPI interface) shall exist for adding:
  - new input artifact types;
  - new node mapping rules;
  - new generation strategies.

## 4. Functional requirements: runtime service

The runtime service — an application based on **Spring Boot + Apache Camel** (stack: Java 25,
Spring Boot 4.x) — is where the whole migrated integration runs. It consumes artifacts prepared by
the converter and executes them.

### 4.1. Composition and deployment model

- **FR-R1.1. (Must)** The runtime service is implemented as a Spring Boot application (Java 25) and is
  the only runtime component of the product: after conversion, everything runs on it.
- **FR-R1.2. (Must)** The converter is delivered separately as a one-shot Java application (CLI):
  started by a command, it terminates when conversion completes, leaves no background processes and
  does not start web services.
- **FR-R1.3. (Must)** The repository is organized as a multi-module Maven build: root `pom.xml`,
  module `converter` and module `runtime`. The build runs with a single Maven command from the root.

### 4.2. Camel routes loading

- **FR-R2.1. (Must)** The service shall load routes from XML DSL `<routes>` located in the `camel/`
  directory on the classpath (`camel/*.xml`) — generated routes are delivered inside the service jar
  (baseline deployment model).
- **FR-R2.2. (Should)** Additionally, route loading from an external directory shall be supported
  (path set via `camel.springboot.routes-include-pattern`), without code changes — for the
  "update routes without rebuilding the jar" scenario.
- **FR-R2.3. (Should)** Route parsing errors at startup shall fail the service (fail-fast) with
  diagnostics pointing at the exact file and line.
- **FR-R2.4. (Could)** Hot reload of routes without a service restart — out of the first version.

### 4.3. Beans, models and mappers

- **FR-R3.1. (Must)** All classes prepared by the converter (processors for Compute nodes, ULM models
  per FR-3.4, MapStruct mappers) are compiled and delivered inside the service jar as Spring
  components; references to them from XML routes (`<bean>`, `method=…`) must resolve in the Spring
  context.
- **FR-R3.2. (Should)** An alternative delivery variant shall be supported: generated classes as a
  separate Maven dependency (library) attached to the `runtime` module.

### 4.4. Connections (adapters)

- **FR-R4.1. (Must)** IBM MQ connectivity via `camel-jms` and the IBM MQ client: connection parameters
  (host, ports, channels, queues), connection pooling, delivery acknowledgement modes — set by external
  configuration (`application.yml` / environment variables), without code changes.
- **FR-R4.2. (Should)** Local JMS transactions support (`transacted`); XA transactions are out of scope
  (see Won't).

### 4.5. Reliability and observability

- **FR-R5.1. (Must)** A single default error handler: `onException`/`errorHandler` with logging and a
  dead letter route (recipient by configuration); generated routes must not require per-route error
  customization in the baseline mode.
- **FR-R5.2. (Should)** Observability: Actuator (`/actuator/health`, `/actuator/metrics`), Micrometer
  metrics (including per-route exchange counters), structured logging with the exchange identifier
  (correlation id) on every line.
- **FR-R5.3. (Should)** Graceful shutdown: on service stop, in-flight exchanges complete and new
  intake stops.
- **FR-R5.4. (Could)** Tracing (OpenTelemetry) across routes.

### 4.6. Configuration and deployment

- **FR-R6.1. (Must)** All configuration (connections, route directories, profiles) — via
  `application.yml` and environment variables; secrets are not stored in the repository.
- **FR-R6.2. (Should)** The service is packaged as an executable jar (spring-boot repackage) and starts
  with a single command; CI/CD scenario: converter → runtime rebuild → jar deploy.
- **FR-R6.3. (Could)** OCI image (Docker) and a deployment template.

## 5. Prioritization of requirements (MoSCoW)

| Priority | Requirements | Meaning of the boundary |
|----------|-------------|------------------------|
| **Must** | FR-1.1–1.5, FR-2.1, FR-2.2 (baseline nodes), FR-2.3, FR-2.5, FR-4.1 (baseline rules), FR-4.3, FR-4.4, FR-5.1, FR-7.1; FR-R1.1–R1.3, FR-R2.1, FR-R2.3, FR-R3.1, FR-R4.1, FR-R5.1, FR-R6.1 | First workable version: the converter parses the main artifacts, generates correct routes and a simple report; the runtime executes them and connects to MQ |
| **Should** | FR-2.2 (extended nodes), FR-2.4, FR-2.6, FR-2.7, FR-3.1–3.5, FR-4.1 (complex rules), FR-4.2, FR-5.2, FR-6.1, FR-7.2, FR-8.1; FR-R2.2, FR-R3.2, FR-R4.2, FR-R5.2, FR-R5.3, FR-R6.2 | Conversion completeness (ESQL, Compute, subflow expansion), traceability, runtime observability |
| **Could** | FR-5.3, FR-6.2, FR-7.3, FR-8.2; FR-R2.4, FR-R5.4, FR-R6.3 | Comfort and extensibility: statistics, SPI, web UI, hot reload, tracing, containerization |
| **Won't** | see section 6 | Deliberately out of scope for the current version |

Planned iterations:

- **v1 (Must)** — a working end-to-end scenario "simple IIB flow → Camel route → runtime service";
- **v2 (Should)** — ESQL, Compute/MapStruct, extended nodes, observability;
- **v3 (Could)** — extensibility and conveniences.

## 6. Not doing in the current version (Won't have)

- Full parsing of DFDL schemas;
- Automatic generation of integration tests from source flows;
- XA transactions and hard delivery guarantees (ported manually);
- Processing of nodes requiring ACE runtime (e.g., parts of non-standard Database node properties);
- Editing source flows/business logic via the system.
