# Project instructions

- Use subagents for independent work when useful. The user authorizes this for all future work in this project. Coordinate ownership of files and validate the combined result.
- Prefer cheaper subagent models (Terra or Luna) for bounded, straightforward fixes and tests. Keep complex design/implementation and final review on a capable model; do not trade correctness for lower token usage.
- When changing code, remove redundant comments. Keep only short comments explaining important constraints, non-obvious decisions, and critical behavior. Preserve useful source references and actionable migration TODOs.
- Follow the priorities in `docs/project-analysis-2026-10-22.ru.md`; record implementation progress separately so the original analysis remains a dated baseline.
- Stack: the converter and compatibility tests use Java 25; the independent runtime uses Java 21, Spring Boot 4.1.2, Apache Camel 4.22.2 LTS, and Jackson 3 XML. Build each project with its required JDK; CI runs them separately.
- Java packages and generated sources use `ru.petrovov.iib_camel`; Maven groupId is `ru.petrovov.iib_camel`.
- Verify conversion changes against freshly generated artifacts and behavior, not only saved golden files.
- The user's source photographs, source artifacts and business examples are confidential. Never publish their contents, concrete entity names, paths, queue/topic/schema/table names, business rules, screenshots, transcriptions, or derived case-specific reports to GitHub (including commits, issues, PRs and comments).
- Keep case-specific material only in the ignored `.private/` directory or outside the repository. Public documentation may describe generic capabilities and requirements. Create public fixtures from scratch with neutral identifiers and synthetic data; do not copy confidential business logic. Before commit/push, review the staged diff and filenames for confidential material. Never force-add private files or put a confidential identifier denylist into tracked files.
