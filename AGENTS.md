# Project instructions

- Use subagents for independent work when useful. The user authorizes this for all future work in this project. Coordinate ownership of files and validate the combined result.
- Prefer cheaper subagent models (Terra or Luna) for bounded, straightforward fixes and tests. Keep complex design/implementation and final review on a capable model; do not trade correctness for lower token usage.
- When changing code, remove redundant comments. Keep only short comments explaining important constraints, non-obvious decisions, and critical behavior. Preserve useful source references and actionable migration TODOs.
- Follow the priorities in `docs/project-analysis-2026-10-22.ru.md`; record implementation progress separately so the original analysis remains a dated baseline.
- Stack: Java 25, Maven, Spring Boot, Apache Camel XML DSL. Run Maven with JDK 25. Use `mvn verify` for the combined converter/runtime checks.
- Verify conversion changes against freshly generated artifacts and behavior, not only saved golden files.
