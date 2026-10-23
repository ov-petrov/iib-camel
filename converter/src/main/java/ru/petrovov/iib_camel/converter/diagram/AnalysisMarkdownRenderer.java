package ru.petrovov.iib_camel.converter.diagram;

import ru.petrovov.iib_camel.converter.camel.RouteGenerator;
import ru.petrovov.iib_camel.converter.msgflow.FlowConnection;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.report.Diagnosis;
import ru.petrovov.iib_camel.converter.report.UnitStatus;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AnalysisMarkdownRenderer {

    private final MermaidDiagramRenderer mermaid;
    private final NodeCategoryRegistry registry;

    public AnalysisMarkdownRenderer(MermaidDiagramRenderer mermaid) {
        this(mermaid, NodeCategoryRegistry.withIibDefaults());
    }

    public AnalysisMarkdownRenderer(MermaidDiagramRenderer mermaid, NodeCategoryRegistry registry) {
        this.mermaid = mermaid;
        this.registry = registry;
    }

    public String render(MsgFlow flow, Orientation orientation) {
        return build(flow, orientation, null);
    }

    public String render(MsgFlow flow, Orientation orientation, String sourceFile) {
        return build(flow, orientation, sourceFile);
    }

    public String render(MsgFlow flow, Orientation orientation, String sourceFile, List<Diagnosis> diagnoses) {
        StringBuilder out = new StringBuilder(build(flow, orientation, sourceFile));
        var issues = diagnoses.stream().filter(d -> d.status() != UnitStatus.CONVERTED).toList();
        out.append("\n## Проверка проекта\n\nГотовность потока в профиле пилота: **")
                .append(issues.isEmpty() ? "да" : "нет").append("**.\n\n")
                .append("Проверены граф, подпотоки, Compute/Mapping-ссылки и поддержка операций в переданном наборе файлов.\n");
        if (!issues.isEmpty()) {
            out.append("\n| Узел | Статус | Причина |\n|---|---|---|\n");
            issues.forEach(d -> out.append("| ").append(escapeCell(d.nodeId())).append(" | ")
                    .append(d.status().label()).append(" | ").append(escapeCell(d.note())).append(" |\n"));
        }
        return out.toString();
    }

    private String build(MsgFlow flow, Orientation orientation, String sourceFile) {
        StringBuilder out = new StringBuilder();
        out.append("# Инвентаризация «как есть»: ").append(titleOf(flow)).append("\n\n");
        out.append("Отчёт конвертера iib-camel (команда `analyze`): визуализация и оценка\n")
           .append("текущей системы IIB **до миграции**.\n\n");
        if (sourceFile != null && !sourceFile.isBlank()) {
            out.append("Источник: `").append(sourceFile).append("`.\n\n");
        }

        out.append("## Топология\n\n")
           .append("```mermaid\n")
           .append(mermaid.render(flow, orientation))
           .append("```\n\n");

        out.append("## Узлы\n\n")
           .append("| Узел | Тип | Категория IIB | Ключевое свойство | Обработка предусмотрена |\n")
           .append("|---|---|---|---|---|\n");
        for (FlowNode node : flow.nodes()) {
            out.append("| `").append(node.id())
               .append("` | ").append(typeOf(node))
               .append(" | ").append(categoryTitle(node.kind()))
               .append(" | ").append(escapeCell(captionOf(node.properties())))
               .append(" | ").append(RouteGenerator.SUPPORTED_BASE_KINDS.contains(node.kind())
                        ? "да" : "нет (ручная миграция)")
               .append(" |\n");
        }

        out.append("\n## Связи\n\n")
           .append("| Источник | Терминал | Приёмник | Терминал |\n|---|---|---|---|\n");
        for (FlowConnection c : flow.connections()) {
            out.append("| `").append(c.sourceNodeId())
               .append("` | ").append(c.sourceTerminal())
               .append(" | `").append(c.targetNodeId())
               .append("` | ").append(c.targetTerminal())
               .append(" |\n");
        }

        out.append("\n## Готовность к миграции\n\n");
        verdict(flow, out);
        return out.toString();
    }

    private void verdict(MsgFlow flow, StringBuilder out) {
        List<FlowNode> nodes = flow.nodes();
        long handled = nodes.stream()
                .filter(n -> RouteGenerator.SUPPORTED_BASE_KINDS.contains(n.kind()))
                .count();
        List<String> unsupportedKinds = nodes.stream()
                .map(FlowNode::kind)
                .distinct()
                .filter(kind -> !RouteGenerator.SUPPORTED_BASE_KINDS.contains(kind))
                .sorted()
                .toList();

        out.append("Обработка всех узлов IIB предусмотрена конвертером: **")
           .append(handled == nodes.size() ? "да" : "нет").append("** (")
           .append(handled).append(" из ").append(nodes.size()).append(" узлов).\n\n");

        if (unsupportedKinds.isEmpty()) {
            out.append("Все типы узлов потока входят в базовый набор правил конвертации\n")
               .append("(FR-2.2, Must): ").append(distinctKinds(nodes)).append(".\n");
        } else {
            out.append("Типы вне покрытия (**").append(unsupportedKinds.size()).append("**): ")
               .append(String.join(", ", unsupportedKinds)).append(". Для них есть два пути:\n")
               .append("- ручная миграция узла (FR-2.7 — в маршруте будет TODO-заглушка,\n")
               .append("  а отчёт `convert` пометит узел статусом «не поддерживается»);\n")
               .append("- расширение правил конвертера (FR-2.2, Should).\n");
        }
        out.append("\nСтатусы каждой единицы конвертации (после миграции) — в отчёте команды `convert`.\n");
        out.append("Это покрытие типов узлов; само по себе оно не подтверждает готовность графа и зависимостей.\n");
    }

    private static String distinctKinds(List<FlowNode> nodes) {
        return nodes.stream()
                .map(FlowNode::kind)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static String typeOf(FlowNode node) {
        return node.kind() == null ? "" : node.kind();
    }

    private String categoryTitle(String kind) {
        return switch (registry.categorize(kind)) {
            case INPUT -> "ввод";
            case OUTPUT -> "вывод";
            case ROUTE -> "маршрутизация";
            case PROCESS -> "обработка";
            case ERROR -> "обработка ошибок";
            case REFERENCE -> "ссылка на подпоток";
            case OTHER -> "прочее";
        };
    }

    private static String titleOf(MsgFlow flow) {
        return DiagramNames.of(flow.name(), "flow");
    }

    private static String captionOf(Map<String, String> properties) {
        String queue = properties.get("queueName");
        if (queue != null && !queue.isBlank()) {
            return "queueName = " + queue;
        }
        String expression = properties.get("computeExpression");
        if (expression != null && expression.contains("#")) {
            return "esql = " + expression.substring(expression.indexOf('#') + 1);
        }
        return "—";
    }

    private static String escapeCell(String text) {
        return text == null ? "" : text.replace("|", "\\|");
    }
}
