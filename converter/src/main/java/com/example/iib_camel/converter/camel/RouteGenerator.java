package com.example.iib_camel.converter.camel;

import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;

import java.util.Optional;

/**
 * Генератор маршрутов Apache Camel (XML DSL) из промежуточной модели — FR-2.1–2.7.
 *
 * Базовые правила (FR-2.2, Must): MQInput → from(jms:…), Compute → bean,
 * Route → choice/when/otherwise (filterPattern → simple), MQOutput → to(jms:…);
 * catch/fail → doCatch (FR-2.3). Неизвестные узлы не отбрасываются, а создают
 * TODO-заглушки (FR-2.7). Каждый шаг несёт комментарий-источник (FR-2.6).
 */
public final class RouteGenerator {

    private static final String CAMEL_NS = "http://camel.apache.org/schema/spring";

    private final MsgFlow flow;

    public RouteGenerator(MsgFlow flow) {
        this.flow = flow;
    }

    public String generate() {
        var out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<routes xmlns=\"").append(CAMEL_NS).append("\">\n");
        for (FlowNode input : flow.nodes()) {
            if ("MQInput".equals(input.kind())) {
                emitRoute(input, out);
            }
        }
        out.append("</routes>\n");
        return out.toString();
    }

    private void emitRoute(FlowNode input, StringBuilder out) {
        out.append(line(1, commentFor(input)))
           .append(line(1, "<route id=\"" + routeId() + "\">"))
           .append(line(2, "<from uri=\"jms:" + queue(input) + "\"/>"))
           .append(line(2, "<doTry>"));

        StringBuilder steps = new StringBuilder();
        walk(input, "out", 3, steps);
        out.append(steps).append(catchBranch(input))
           .append(line(2, "</doTry>"))
           .append(line(1, "</route>"));
    }

    /** Обход цепочки по исходящему терминалу. */
    private void walk(FlowNode node, String terminal, int indent, StringBuilder out) {
        connection(node.id(), terminal).ifPresent(link -> {
            FlowNode target = flow.node(link.targetNodeId());
            switch (target.kind()) {
                case "Compute" -> {
                    out.append(step(indent, target, "<to uri=\"bean:" + beanId(target) + "\"/>"));
                    walk(target, "out", indent, out);
                }
                case "Route" -> emitChoice(target, indent, out);
                case "MQOutput" -> out.append(step(indent, target, "<to uri=\"jms:" + queue(target) + "\"/>"));
                default -> out.append(unknownNode(indent, target));
            }
        });
    }

    private void emitChoice(FlowNode route, int indent, StringBuilder out) {
        out.append(line(indent, commentFor(route))).append(line(indent, "<choice>"));
        route.dynamicOutTerminals().stream()
                .filter(t -> !"Default".equals(t))
                .forEach(terminal -> out.append(line(indent + 1, "<when>"))
                        .append(line(indent + 2, "<simple>" + toSimple(route) + "</simple>"))
                        .append(branch(indent + 2, route, terminal))
                        .append(line(indent + 1, "</when>")));
        connection(route.id(), "Default").ifPresent(link ->
                out.append(line(indent + 1, "<otherwise>"))
                   .append(branch(indent + 2, route, "Default"))
                   .append(line(indent + 1, "</otherwise>")));
        out.append(line(indent, "</choice>"));
    }

    private StringBuilder branch(int indent, FlowNode route, String terminal) {
        StringBuilder out = new StringBuilder();
        walk(route, terminal, indent, out);
        return out;
    }

    private StringBuilder catchBranch(FlowNode input) {
        StringBuilder out = new StringBuilder();
        for (String terminal : new String[] {"catch", "failure"}) {
            Optional<com.example.iib_camel.converter.msgflow.FlowConnection> link = connection(input.id(), terminal);
            if (link.isPresent()) {
                FlowNode target = flow.node(link.get().targetNodeId());
                out.append(line(3, "<doCatch>"))
                   .append(line(4, "<exception>java.lang.Exception</exception>"))
                   .append(line(4, commentFor(target)))
                   .append(line(4, "<to uri=\"jms:" + queue(target) + "\"/>"))
                   .append(line(3, "</doCatch>"));
                break; // одна ветвь обработки ошибок (базовый режим, FR-2.2 catching)
            }
        }
        return out;
    }

    private StringBuilder step(int indent, FlowNode node, String element) {
        return new StringBuilder()
                .append(line(indent, commentFor(node)))
                .append(line(indent, element));
    }

    private String unknownNode(int indent, FlowNode node) {
        return line(indent, "<!-- TODO (FR-2.7): правило конвертации узла \"" + node.kind() + "\" не определено -->")
             + line(indent, "<log message=\"Unsupported IIB node: " + node.kind() + "\"/>");
    }

    private Optional<com.example.iib_camel.converter.msgflow.FlowConnection> connection(String nodeId, String terminal) {
        return flow.connections().stream()
                .filter(c -> c.sourceNodeId().equals(nodeId) && c.sourceTerminal().equals(terminal))
                .findFirst();
    }

    /** filterPattern $Body/Adapter/Order/status='HIGH' → simple "${body.order.status} == 'HIGH'" (FR-3.2). */
    static String toSimple(FlowNode route) {
        String pattern = route.properties().getOrDefault("filterTable.filterPattern", "");
        int eq = pattern.indexOf('=');
        if (eq < 0) {
            return "TODO simple for: " + pattern;
        }
        String path = pattern.substring(0, eq).strip();
        String value = pattern.substring(eq + 1).strip();
        String[] segments = path.replaceFirst("^\\$Body/", "").split("/");
        StringBuilder body = new StringBuilder("${body");
        for (int i = 1; i < segments.length; i++) { // первый сегмент = корень модели
            body.append('.').append(lowerFirst(segments[i]));
        }
        return body.append("} ").append(eq < 0 ? "" : (value.startsWith("'") ? "== " : "> "))
                   .append(value).toString();
    }

    static String beanId(FlowNode compute) {
        String expression = compute.properties().getOrDefault("computeExpression", "");
        int hash = expression.indexOf('#');
        String module;
        if (hash < 0) {
            module = compute.id();
        } else {
            module = expression.substring(hash + 1);
            int dot = module.indexOf('.');
            module = dot < 0 ? module : module.substring(0, dot);
        }
        return camelize(module);
    }

    /** Adapter_Compute1 → adapterCompute1 */
    static String camelize(String s) {
        var parts = s.split("[_-]");
        var result = new StringBuilder(lowerFirst(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return result.toString();
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String queue(FlowNode mq) {
        return mq.properties().getOrDefault("queueName", "TODO-queue-not-set");
    }

    private String routeId() {
        return flow.name().replaceAll("\\.msgflow$", "");
    }

    private static String commentFor(FlowNode node) {
        return "<!-- source: узел " + node.id() + " (" + node.kind() + ") -->";
    }

    private static String line(int indent, String text) {
        return INDENT(indent) + text + "\n";
    }

    private static String INDENT(int level) {
        return "  ".repeat(level);
    }
}
