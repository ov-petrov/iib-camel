# Project instructions

- Use subagents for independent work when useful. The user authorizes this for all future work in this project. Coordinate ownership of files and validate the combined result.
- Prefer cheaper subagent models (Terra or Luna) for bounded, straightforward fixes and tests. Keep complex design/implementation and final review on a capable model; do not trade correctness for lower token usage.
- Do not add source comments or Javadoc by default, including in generated code. Keep explanations in documentation. The
  only current source-comment exceptions are the six specific cases listed in `docs/code-comment-exceptions.ru.md`;
  parser-test input strings remain test data.
- Follow the priorities in `docs/project-analysis-2026-10-22.ru.md`; record implementation progress separately so the original analysis remains a dated baseline.
- Stack: the converter and compatibility tests use Java 25; the independent runtime uses Java 21, Spring Boot 4.1.2, Apache Camel 4.22.2 LTS, and Jackson 3 XML. Build each project with its required JDK; CI runs them separately.
- Runtime configuration uses YAML. Bind grouped application settings with `@ConfigurationProperties`; use Lombok for
  routine accessors and prefer `@RequiredArgsConstructor` for plain constructor injection. Keep explicit constructors
  when they perform necessary initialization or validation. Preserve validation and credential redaction.
- Inject reusable runtime infrastructure from Spring beans, including `JdbcTemplate`, JSON mapping, and transaction
  templates. Use Liquibase for runtime database migrations. Configure the delivery schema centrally and qualify table
  names explicitly; do not rely on a connection's ambient `search_path`.
- Represent shared domain values, especially lifecycle statuses, with enums; use named constants for repeated protocol
  identifiers and configuration keys. Do not scatter magic string literals through business logic or SQL parameters.
  Bind enum values as query parameters; preserve historical database migrations.
- Keep POM and Java code readable: separate logical sections with blank lines, group related dependencies, and separate
  fields, constructors, and methods. Avoid compressed one-line methods and control flow when they hinder readability.
- Organize Java packages by responsibility and functionality; keep configuration, handler contracts, inbox processing,
  outbox delivery, and shared JDBC support in their respective packages. Mirror the structure in tests and keep
  implementation details package-private where possible.
- Java packages and generated sources use `ru.petrovov.iib_camel`; Maven groupId is `ru.petrovov.iib_camel`.
- Keep `LICENSE` and `NOTICE` only at the repository root; package them into artifacts from that location. Preserve
  third-party license notices in the root `NOTICE`.
- Verify conversion changes against freshly generated artifacts and behavior, not only saved golden files.
- The user's source photographs, source artifacts and business examples are confidential. Never publish their contents, concrete entity names, paths, queue/topic/schema/table names, business rules, screenshots, transcriptions, or derived case-specific reports to GitHub (including commits, issues, PRs and comments).
- Keep case-specific material only in the ignored `.private/` directory or outside the repository. Public documentation may describe generic capabilities and requirements. Create public fixtures from scratch with neutral identifiers and synthetic data; do not copy confidential business logic. Before commit/push, review the staged diff and filenames for confidential material. Never force-add private files or put a confidential identifier denylist into tracked files.
