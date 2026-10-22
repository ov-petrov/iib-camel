# iib-camel

A prototype for analyzing IBM Integration Bus (IIB) / IBM App Connect Enterprise (ACE) message flows and converting a supported subset to Apache Camel XML routes and Java.

> [Русская версия](README.ru.md)

## What it does

The `analyze` command reads `.msgflow`, `.subflow`, and `.esql` inputs. It writes flow diagrams and reports, plus an ESQL module inventory when ESQL is present. The `convert` command generates Camel XML routes, Java beans for the supported ESQL subset, and a conversion report.

The current ESQL profile is deliberately limited: Compute `Main` functions without parameters, `RETURNS BOOLEAN`, supported `SET` operations, and a final `RETURN TRUE`. Unsupported or incomplete ESQL is reported and its generated bean stops before running business operations. The XML route profile handles a tested set of flow nodes, route filters, subflows, and error paths; unsupported or ambiguous graph features are reported. Read [implementation progress](docs/implementation-progress.ru.md) and the [manual analyze walkthrough](examples/analyze/README.ru.md) for details.

This is an experimental migration aid, not a complete IIB/ACE parser or a production migration service. It does not currently provide MapStruct generation, message-set/schema parsing, complete ESQL translation, or IBM MQ integration. Generated output must be reviewed and validated against the source application and target environment.

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
