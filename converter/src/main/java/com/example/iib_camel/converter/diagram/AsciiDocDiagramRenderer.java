package com.example.iib_camel.converter.diagram;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Рендерер IR → AsciiDoc-документ (.adoc)
 * (docs/diagram-renderer-design.ru.md, раздел 4.4/4.5, формат adoc).
 *
 * Состав документа: заголовок (= имя потока), ASCII-конспект в листинг-блоке (читается
 * и как текст, и как диаграмма после конвертации asciidoctor), таблицы узлов и связей.
 * Детерминированный (D-3/D-4): узлы/связи в порядке объявления IR, без времени и случайностей.
 * Ascii-глифы дерева (├─ …) в документе допустимы — UTF-8 Asciidoctor отдаёт и для текста.
 */
public final class AsciiDocDiagramRenderer {

    private final AsciiDiagramRenderer treeRenderer = new AsciiDiagramRenderer();

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

        out.append("// Сгенерировано конвертером iib-camel (diagram --format adoc, веха M1).\n")
           .append("= ").append(title).append("\n");
        if (sourceFile != null && !sourceFile.isBlank()) {
            out.append(":source-file: ").append(sourceFile).append("\n");
        }
        out.append("\n")
           .append("Файл потока сообщений IIB из корпуса конвертеров.\n\n");

        out.append("== Диаграмма\n\n")
           .append("Дерево-конспект читается как текст и как диаграмма после конвертации\n")
           .append("asciidoctor; детали связей — в таблице ниже.\n\n")
           .append("----\n")
           .append(treeRenderer.render(flow))
           .append("----\n\n");

        out.append("== Узлы\n\n")
           .append("[cols=\"1,1,2,2\" options=\"header\"]\n")
           .append("|===\n")
           .append("| id | тип | имя | свойства\n")
           .append(rowsOfNodes(flow.nodes()))
           .append("|===\n\n");

        out.append("== Связи\n\n")
           .append("[cols=\"1,1,2,1\" options=\"header\"]\n")
           .append("|===\n")
           .append("| source | терминал | target | терминал\n")
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
            rows.append(" | ").append(caption == null ? "—" : escapeCell(caption)).append("\n");
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
     * ячеек, поэтому ее удобно заэскейпить конвертацией в сущность HTML.
     */
    private static String escapeCell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "&#124;");
    }
}
