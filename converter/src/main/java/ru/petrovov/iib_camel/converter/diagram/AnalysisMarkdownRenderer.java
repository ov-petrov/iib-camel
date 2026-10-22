package ru.petrovov.iib_camel.converter.diagram;

import ru.petrovov.iib_camel.converter.camel.RouteGenerator;
import ru.petrovov.iib_camel.converter.msgflow.FlowConnection;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Рендерер инвентаризационного отчёта «как есть» (Markdown, .md) — формирует команда
 * {@code analyze}. Это отчёт ТОЛЬКО о текущей системе, до миграции: он не содержит
 * статусов конвертации и артефактов целевого вида (маршрут, бины) — это подчёркивает
 * границу с полным отчётом команды convert (FR-5.1).
 *
 * Состав документа:
 *   - «Топология» — блок ```mermaid``` со встроенной схемой (GitHub/IDEA рендерятся);
 *   - «Узлы» — таблица: узел, тип, категория IIB, ключевое свойство, есть ли правило
 *     конвертации (сверка с RouteGenerator.SUPPORTED_BASE_KINDS);
 *   - «Связи» — таблица терминалов;
 *   - «Готовность к миграции» — вердикт: предусмотрена ли обработка всех узлов/сущностей
 *     IIB, и какие типы требуют ручной миграции или расширения правил (FR-2.2, Should).
 *
 * Детерминированный (D-3/D-4): обход только по упорядоченным коллекциям IR.
 */
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

    /** Отчёт без атрибута источника (используется в тестах). */
    public String render(MsgFlow flow, Orientation orientation) {
        return build(flow, orientation, null);
    }

    /** Отчёт со ссылкой на исходный файл (путь подаёт CLI — IR его не хранит). */
    public String render(MsgFlow flow, Orientation orientation, String sourceFile) {
        return build(flow, orientation, sourceFile);
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

    /** Вердикт: обработка всех узлов предусмотрена или нет, какие сущности вне покрытия. */
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

    /** Самое содержательное свойство узла (A5): очередь либо ESQL-модуль. */
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
