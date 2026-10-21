# iib-camel

A migration tool for converting IBM Integration Bus (IIB / IBM App Connect Enterprise, ACE) applications
to a modern Java stack based on **Spring Boot** and **Apache Camel**.

> 🇷🇺 Русская версия: [README.ru.md](README.ru.md)

## Purpose

Legacy integration applications on IBM Integration Bus — message flows (`.msgflow`), subflows
(`.subflow`), ESQL modules (`.esql`), message sets (`.mset`, XSD/DFDL/JSON schemas) — are uploaded to the
tool as input.

On the output, the tool produces artifacts ready for a Spring Boot + Apache Camel application:

- **Apache Camel routes** in XML DSL format;
- **Java classes** for message transformation;
- **MapStruct mapper classes** generated from Compute / Mapping / ESQL logic;
- configuration skeleton (`application.properties` / `application.yml`).

The tool does **not** replace the developer, but removes up to the majority of the routine migration work:
skeleton routes, node wiring, mapping skeletons and transformation boilerplate are generated automatically,
the developer then completes and validates the business logic.

## How it works

Migration pipeline:

```
 ┌───────────────┐   ┌──────────────┐   ┌───────────────┐   ┌──────────────┐
 │ IIB artifacts │ → │   Parsing    │ → │  Intermediary │ → │  Generation  │
 │ .msgflow      │   │  (XML, ESQL) │   │  model (DOM)  │   │  (routes,    │
 │ .subflow      │   │              │   │               │   │  mappers)    │
 │ .esql         │   └──────────────┘   └───────────────┘   └──────────────┘
 │ message sets  │           │                                        │
 └───────────────┘           │              ┌───────────────┐         │
                             └── problem ───────────────────────────────────┐
                               report           │ validation │ ←──── for review
                                                └───────────────┘
```

1. **Parsing** — reading and validating IIB project files, building an intermediary model (internal
   representation of flows and transformations).
2. **Transformation** — matching IIB nodes to Apache Camel elements (see
   [functional requirements](docs/functional-requirements.en.md)).
3. **Generation** — output of Camel XML routes, Java classes, MapStruct mappers, configurations.
4. **Report** — a migration report: what was converted, which nodes require manual work.

## Product components and repository structure

The project is a multi-module Maven build with two independent applications:

```
iib-camel/
├── pom.xml        ← parent (aggregator): one Maven build for both modules
├── converter/     ← one-shot CLI: IIB artifacts → Camel XML DSL + Java + MapStruct + report
└── runtime/       ← Spring Boot service executing the converted Camel routes
```

```
converter (runs on command, terminates)         runtime (runs 24/7)
        │                                              ▲
        ▼                                              │
   Camel XML routes, Java, MapStruct  ── jar/classpath ┘
```

- **converter** — a separate one-shot Java application: started by a command, converts the specified
  artifacts and terminates, leaving no background processes or web services; used by integration
  engineers and CI/CD.
- **runtime** — *the service where everything runs after conversion*: Spring Boot + Apache Camel,
  routes loaded from `runtime/src/main/resources/camel/*.xml` (classpath) or from an external
  directory, IBM MQ adapters (`camel-jms`), unified error handling, health/metrics.

## Key capabilities

- Parsing of `.msgflow`, `.subflow`, `.esql` and message set files;
- Batch conversion of a whole IIB project or a selected directory;
- Decomposition of subflows into embedded steps of the target routes;
- ESQL translation: expressions, predicates, `WHERE` and `SET` clauses;
- Generation of MapStruct mappers from message transformation logic;
- Migration report with a list of non-convertible elements and required manual changes;
- Traceability: generated elements contain references to the source flow and the source node.

Detailed requirements are described in [Functional requirements](docs/functional-requirements.en.md)
(English), [Функциональные требования](docs/functional-requirements.ru.md) (Russian).

## Technology stack

| Layer            | Technology                                        |
|------------------|----------------------------------------------------|
| Language         | Java 25                                            |
| Build            | Maven (multi-module)                               |
| converter        | Java 25 CLI (standalone one-shot application)      |
| runtime          | Spring Boot 4.x + Apache Camel 4.x (XML DSL)       |
| Message mapping  | MapStruct (generated as a Spring bean)             |
| Testing          | JUnit 5, golden files, embedded Artemis broker     |

## Project status

🚧 The project is at an early stage. Repository structure is set up (multi-module Maven: `converter` + `runtime`),
requirements (MoSCoW) and the testing strategy are fixed; converter implementation is starting.

## Documentation

- [README.ru.md](README.ru.md) — Russian readme
- [Functional requirements (RU)](docs/functional-requirements.ru.md)
- [Functional requirements (EN)](docs/functional-requirements.en.md)
- [Testing strategy (EN)](docs/test-strategy.en.md) & [Testing strategy (RU)](docs/test-strategy.ru.md)
- [Diagram renderer design (EN)](docs/diagram-renderer-design.en.md) & [Design: diagram renderer (RU)](docs/diagram-renderer-design.ru.md)
