package ru.petrovov.iib_camel.converter.diagram;

import ru.petrovov.iib_camel.converter.msgflow.FlowConnection;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Рендерер IR → AsciiDoc-документ (.adoc)
 * (docs/diagram-renderer-design.ru.md, раздел 4.4/4.5, формат adoc).
 *
 * Состав документа: заголовок (= имя потока), атрибут :source-file:, отсылка к графической
 * схеме (.mmd рядом, сборщик рендерит через GitHub/asciidoctor-mechanism), таблицы узлов
 * и связей. Детерминированный (D-3/D-4): узлы/связи в порядке объявления IR, без времени
 * и случайностей.
 *
 * Историческая заметка: раньше документ содержал «дерево-конспект» (ASCII-схему) в
 * листинг-блоке; ASCII-формат удалён — топология живёт в Mermaid (.mmd и встроенный
 * блок в отчёте .md команды analyze).
 */
public final class AsciiDocDiagramRenderer {

    /** Готовит документ .adoc по потоку (без атрибута источника). */
    public String render(MsgFlow flow) {
        return build(flow, null);
    }

    /** Готовит документ .adoc с атрибутом :source-file: (путь подаёт CLI — IR его не хранит). */
    public String render(MsgFlow flow, String sourceFile) {
        return build(flow, sourceFile);
    }

    private String build(MsgFlow flow, String sourceFile) {
        StringBuilder out = new StringBuilder();
        String title = DiagramNames.of(flow.name(), "flow");

        out.append("// Сгенерировано конвертером iib-camel (analyze --format adoc).\n")
           .append("= ").append(title).append("\n");
        if (sourceFile != null && !sourceFile.isBlank()) {
            out.append(":source-file: ").append(sourceFile).append("\n");
        }
        out.append("\n")
           .append("Поток сообщений IIB: структурная схема, узлы (id, тип, ключевое свойство)\n")
           .append("и связи по терминалам.\n\n");

        out.append("== Схема\n\n")
           .append("Графическая схема потока — в файле ").append(title).append(".mmd рядом\n")
           .append("(этот же блок встроен в MD-отчёт команды analyze).\n\n");

        out.append("== Узлы\n\n")
           .append("[cols=\"1,1,2,2\" options=\"header\"]\n")
           .append("|===\n")
           .append("| id | тип | имя | ключевое свойство\n")
           .append(rowsOfNodes(flow.nodes()))
           .append("|===\n\n");

        out.append("== Связи\n\n")
           .append("[cols=\"1,1,2,1\" options=\"header\"]\n")
           .append("|===\n")
           .append("| источник | терминал | приёмник | терминал\n")
           .append(rowsOfConnections(flow.connections()))
           .append("|===\n");
        return out.toString();
    }

    private static String rowsOfNodes(List<FlowNode> nodes) {
        StringBuilder rows = new StringBuilder();
        for (FlowNode node : nodes) {
            rows.append("| ").append(escapeCell(node.id()))
                .append(" | ").append(escapeCell(node.kind() == null ? "" : node.kind()))
                .append(" | ").append(escapeCell(labelOf(node)));
            String caption = captionOf(node.properties());
            rows.append(" | ").append(escapeCell(caption == null ? "—" : caption)).append("\n");
        }
        return rows.toString();
    }

    private static String rowsOfConnections(List<FlowConnection> connections) {
        StringBuilder rows = new StringBuilder();
        for (FlowConnection connection : connections) {
            rows.append("| ").append(escapeCell(connection.sourceNodeId()))
                .append(" | ").append(escapeCell(connection.sourceTerminal()))
                .append(" | ").append(escapeCell(connection.targetNodeId()))
                .append(" | ").append(escapeCell(connection.targetTerminal()))
                .append("\n");
        }
        return rows.toString();
    }

    private static String labelOf(FlowNode node) {
        return node.label().isBlank() ? node.id() : node.label();
    }

    /** Самое содержательное свойство (A5): очередь либо ESQL-модуль. */
    private static String captionOf(Map<String, String> properties) {
        String queue = properties.get("queueName");
        if (queue != null && !queue.isBlank()) {
            return "queueName = " + queue;
        }
        String expression = properties.get("computeExpression");
        if (expression != null && expression.contains("#")) {
            return "esql = " + expression.substring(expression.indexOf('#') + 1);
        }
        return null;
    }

    /**
     * Экранирование для текста ячейки таблицы AsciiDoc: вертикальная черта — разделитель
     * ячеек, поэтому её удобно заэскейпить конвертацией в сущность HTML.
     */
    private static String escapeCell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "&#124;");
    }
}
