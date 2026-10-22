package com.example.iib_camel.converter.esql;

import com.example.iib_camel.converter.report.UnitStatus;

import java.util.ArrayList;
import java.util.List;

public final class EsqlInventoryRenderer {

    public record EsqlFileInfo(String fileName, List<EsqlModule> modules, String error) {
    }

    public record RenderResult(String markdown, long total, long full, long partial, long outliers) {
    }

    private static final class Counts {
        long total;
        long full;
        long partial;
        long outliers;
    }

    private static final class ModuleScore {
        long converted;
        long totalStatements;
        List<String> issues = new ArrayList<>();
    }

    public RenderResult render(List<EsqlFileInfo> files) {
        var counts = new Counts();
        var rows = new StringBuilder();

        for (EsqlFileInfo file : files) {
            if (file.error() != null) {
                rows.append("| `").append(file.fileName()).append("` | — | ФАЙЛ | — | — | — | ")
                        .append("ошибка разбора: ").append(file.error()).append(" |\n");
                counts.outliers++;
                continue;
            }
            for (EsqlModule module : file.modules()) {
                counts.total++;
                ModuleScore score = score(module);
                String verdict = verdict(module, score, counts);
                rows.append("| `").append(file.fileName()).append("` | ").append(module.name())
                        .append(" | ").append(module.type())
                        .append(" | ").append(module.functions().size())
                        .append(" | ").append(score.totalStatements)
                        .append(" | ").append(score.issues.size())
                        .append(" | ").append(verdict).append(" |\n");
            }
        }

        var out = new StringBuilder();
        out.append("# Инвентарь ESQL «как есть»\n\n")
                .append("Отчёт конвертера iib-camel (команда `analyze`): модули ESQL текущей системы\n")
                .append("IIB **до миграции**. Оценка переводимости — по базовому подмножеству FR-3.2\n")
                .append("(корень `OutputRoot = InputRoot`, MQMD-поля, строковые поля тела, CAST-литералы);\n")
                .append("артефакты перевода (бины, модели) на этой стадии не создаются — их статусы\n")
                .append("после конвертации покажет отчёт команды `convert` (FR-5.1).\n\n");

        out.append("| Файл | Модуль | Тип | Функций | Операторов (SET) | Вне подмножества | Переводимость |\n")
                .append("|---|---|---|---|---|---|---|\n")
                .append(rows);

        out.append("\n## Сводка переводимости\n\n")
                .append("Модулей ESQL: **").append(counts.total).append("**; переводимо полностью ")
                .append(counts.full).append(", частично ").append(counts.partial)
                .append(", вне подмножества ").append(counts.outliers).append(".\n\n")
                .append("Пути продвижения:\n")
                .append("- вне подмножества — ручная доработка после генерации (FR-3.3: TODO-заглушки в бинах)\n")
                .append("  или расширение правил переводчика (FR-3.2, Should);\n")
                .append("- сколько операторов и из-за чего не переведено — покажет отчёт `convert`\n")
                .append("  (пооператорная детализация FR-5.2).\n");
        return new RenderResult(out.toString(), counts.total, counts.full, counts.partial, counts.outliers);
    }

    private static String verdict(EsqlModule module, ModuleScore score, Counts counts) {
        if (score.issues.stream().anyMatch(i -> i.equals("Main не найдена"))) {
            counts.outliers++;
            return "вне подмножества (Main не найдена)";
        }
        if (score.issues.isEmpty()) {
            counts.full++;
            return "переводимо полностью";
        }
        if (score.converted > 0) {
            counts.partial++;
            return "переводимо частично (" + score.converted + " операторов переведено)";
        }
        counts.outliers++;
        return "вне подмножества";
    }

    private ModuleScore score(EsqlModule module) {
        ModuleScore score = new ModuleScore();
        EsqlFunction main = module.functions().stream()
                .filter(f -> f.name().equalsIgnoreCase("Main"))
                .findFirst()
                .orElse(null);
        if (main == null) {
            score.issues.add("Main не найдена");
            return score;
        }
        EsqlTranslator.FunctionTranslation translation = new EsqlTranslator(main).translate();
        score.converted = translation.statements().stream()
                .filter(s -> s.status() == UnitStatus.CONVERTED)
                .count();
        score.totalStatements = translation.statements().size();
        score.issues = new ArrayList<>(translation.issues());
        for (EsqlFunction function : module.functions()) {
            if (function != main) {
                score.issues.add("дополнительная функция " + function.name() + " не переводится автоматически");
            }
        }
        return score;
    }
}
