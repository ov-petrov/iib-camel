# iib-camel

A prototype for analyzing IBM Integration Bus (IIB) / IBM App Connect Enterprise (ACE) message flows and converting a supported subset to Apache Camel XML routes and Java.

> [Русская версия](README.ru.md)

## What it does

The `analyze` command reads `.msgflow`, `.subflow`, and `.esql` inputs. It writes flow diagrams and reports, plus an ESQL module inventory when ESQL is present. The `convert` command generates Camel XML routes, Java beans for the supported ESQL subset, and a conversion report.

The ESQL profile supports a limited set of Compute `Main` functions, including selected `SET` operations, conditional branches, expressions, and explicit casts. Unsupported or incomplete ESQL is reported and its generated bean stops before running business operations. The converter also generates models for a subset of XSD and JSON Schema and MapStruct processors for simple same-type XMLNSC field moves in IBM `.map` files. These features do not cover full ESQL, ULM, or IBM Mapping semantics. `convert` applies a strict readiness gate by default; unsupported or ambiguous project features need review before using generated routes. See [implementation progress](docs/implementation-progress.ru.md), the [pilot profile](docs/pilot-profile.ru.md), and the [deployment guide](deploy/README.ru.md) for the supported limits and required runtime checks.

This is an experimental migration aid, not a complete IIB/ACE parser or a production migration service. IBM MQ configuration is available as an optional profile, but end-to-end IBM MQ compatibility has not been verified; Apache Artemis is the pilot broker. Generated output must be reviewed and validated against the source application and target environment.

## Build

Requirements: JDK 25 and Maven.

```sh
mvn -B verify
```

The build creates the converter JAR at `converter/target/converter.jar`.

## CLI examples

Analyze the included demonstration inputs and write Mermaid, AsciiDoc, and Markdown reports:

```sh
java -jar converter/target/converter.jar analyze \
  --input examples/analyze/system \
  --output target/manual-analysis \
  --format all
```

Convert the included test corpus to Camel XML and Java output:

```sh
java -jar converter/target/converter.jar convert \
  --input converter/src/test/resources/corpus \
  --output target/converted
```

Generated artifacts are written to the selected output directory. Review the command report and any unsupported-operation diagnostics before using the results.

## Project layout

- `converter/` — one-shot Java CLI for analysis and conversion.
- `runtime/` — Spring Boot and Apache Camel runtime used by generated routes and beans.
- `examples/analyze/` — small illustrative inputs for the `analyze` command.
- `docs/` — requirements, design notes, migration guidance, and implementation status.

## Documentation

- [Functional requirements](docs/functional-requirements.en.md)
- [Testing strategy](docs/test-strategy.en.md)
- [Implementation progress (RU)](docs/implementation-progress.ru.md)
- [Manual analysis examples (RU)](examples/analyze/README.ru.md)

## License

This project is licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution information.
