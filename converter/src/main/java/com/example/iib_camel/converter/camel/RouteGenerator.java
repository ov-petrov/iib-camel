package com.example.iib_camel.converter.camel;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.report.Diagnosis;
import com.example.iib_camel.converter.report.RouteResult;
import com.example.iib_camel.converter.report.UnitStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Генератор маршрутов Apache Camel (XML DSL) из промежуточной модели — FR-2.1–2.7.
 *
 * Базовые правила (FR-2.2, Must): MQInput → from(jms:…), Compute → bean,
 * Route → choice/when/otherwise (filterPattern → simple), MQOutput → to(jms:…);
 * подпоток (.subflow) → вызов `<to uri="direct:Имя"/>` + вложенный `<route>` с
 * развёрнутым содержимым подпотока; catch/fail → doCatch (FR-2.3). Неизвестные узлы
 * не отбрасываются, а создают TODO-заглушки (FR-2.7). Каждый шаг несёт
 * комментарий-источник (FR-2.6).
 *
 * Вместе с XML собирает диагнозы по каждому узлу (FR-5.1/5.2): статус единицы
 * конвертации (конвертировано / частично / не поддерживается) + примечание
 * с целью «элемент Camel». Узлы, не посещённые при обходе, получают честный
 * статус «частично»/«не поддерживается» — конвертер не молчит о пропущенных узлах.
 *
 * Подпотоки (FR-2.2): конструктор принимает реестр разобранных .subflow (ключ — имя
 * из nsURI без расширения и полное имя с расширением). Узел-ссылка Subflow разворачивается
 * во вложенный `<route id="Имя">` (from direct:Имя); диагнозы узлов ВНУТРИ подпотока
 * в итог родительского потока не входят — их собирает {@link #inlineResult()}, который
 * CLI вызывает для каждого .subflow отдельно (юнит отчёта — сам файл подпотока).
 * Неразрешённая ссылка → заглушка NOT_SUPPORTED (FR-2.7), продолжение обхода по out.
 */
public final class RouteGenerator {

    private static final String CAMEL_NS = "http://camel.apache.org/schema/spring";
    /** Базовый набор узлов (FR-2.2, Must/Should) — используется генератором и отчётом FR-5.1. */
    public static final Set<String> SUPPORTED_BASE_KINDS =
            Set.of("MQInput", "MQOutput", "Compute", "Route", "Subflow", "Input", "Output");

    /** Лимит вложенности подпотоков — защита от циклических ссылок (заглушка вместо зависания). */
    private static final int MAX_SUBFLOW_DEPTH = 16;

    private final MsgFlow flow;
    private final Map<String, MsgFlow> subflows;

    public RouteGenerator(MsgFlow flow) {
        this(flow, Map.of());
    }

    /** Реестр подпотоков: ключи — имя из nsURI (без .subflow) и полное имя файла. */
    public RouteGenerator(MsgFlow flow, Map<String, MsgFlow> subflows) {
        this.flow = flow;
        this.subflows = subflows;
    }

    /** XML + полный перечень диагнозов всех узлов потока (для отчёта FR-5.1). */
    public RouteResult generate() {
        var out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<routes xmlns=\"").append(CAMEL_NS).append("\">\n");

        List<Diagnosis> raw = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> referenced = new LinkedHashSet<>(); // ключи подпотоков в порядке встречи
        boolean anyInput = false;
        for (FlowNode input : flow.nodes()) {
            if ("MQInput".equals(input.kind())) {
                anyInput = true;
                visited.add(input.id());
                diagnoseInput(input, raw);
                emitRoute(input, out, raw, visited, referenced, 0);
            }
        }
        for (String key : referenced) {
            out.append(inlineRouteXml(subflows.get(key)));
        }
        if (!referenced.isEmpty()) {
            out.append("\n"); // читаемость: вложенные подпотоки отделены от родительских маршрутов
        }
        raw.addAll(orphanDiagnoses(flow, visited, anyInput,
                "нет пути от входного узла MQInput — узел не попал в маршрут",
                "в потоке нет входного узла MQInput — маршрут не сгенерирован"));
        out.append("</routes>\n");
        return new RouteResult(out.toString(), canonical(raw));
    }

    /**
     * Диагнозы юнита-подпотока для отчёта FR-5.1: обход от Input-узлов (eflow:FCMSource)
     * без построения XML (в родительском маршруте подпоток уже вызван через direct:).
     * Не достигнутые узлы получают честный статус с причиной.
     */
    public RouteResult inlineResult() {
        var throwaway = new StringBuilder();
        List<Diagnosis> raw = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        boolean anyEntry = false;
        for (FlowNode entry : flow.nodes()) {
            if ("Input".equals(entry.kind())) {
                anyEntry = true;
                visited.add(entry.id());
                raw.add(new Diagnosis(entry.id(), entry.kind(), UnitStatus.CONVERTED,
                        "вход подпотока (eflow:FCMSource) → from direct-вызова"));
                walk(entry, "out", 2, throwaway, raw, visited, referencedUnused(), flow, 0);
            }
        }
        raw.addAll(orphanDiagnoses(flow, visited, anyEntry,
                "нет пути от Input-узла внутри подпотока — узел не попал в разворачиваемый маршрут",
                "в подпотоке нет узла Input (eflow:FCMSource) — содержимое не разворачивается"));
        return new RouteResult("", canonical(raw));
    }

    /** Реестр-заглушка для рекурсивных вызовов walk, которым не нужно копить ссылки. */
    private Set<String> referencedUnused() {
        return new LinkedHashSet<>();
    }

    private void emitRoute(FlowNode input, StringBuilder out, List<Diagnosis> raw,
                           Set<String> visited, Set<String> referenced, int depth) {
        out.append(line(1, commentFor(input)))
           .append(line(1, "<route id=\"" + routeId(flow.name()) + "\">"))
           .append(line(2, "<from uri=\"jms:" + queue(input) + "\"/>"))
           .append(line(2, "<doTry>"));

        StringBuilder steps = new StringBuilder();
        walk(input, "out", 3, steps, raw, visited, referenced, flow, depth);
        out.append(steps).append(catchBranch(input, raw, visited))
           .append(line(2, "</doTry>"))
           .append(line(1, "</route>"));
    }

    /**
     * Обход цепочки по исходящему терминалу. source — поток, содержащий узел
     * (родительский или подпоток при разворачивании).
     */
    private void walk(FlowNode node, String terminal, int indent, StringBuilder out,
                      List<Diagnosis> raw, Set<String> visited, Set<String> referenced,
                      MsgFlow source, int depth) {
        connection(source, node.id(), terminal).ifPresent(link -> {
            FlowNode target = source.node(link.targetNodeId());
            visited.add(target.id());
            switch (target.kind()) {
                case "Compute" -> {
                    diagnoseCompute(target, raw);
                    out.append(step(indent, target, "<to uri=\"bean:" + beanId(target) + "\"/>"));
                    walk(target, "out", indent, out, raw, visited, referenced, source, depth);
                }
                case "Route" -> {
                    diagnoseRoute(target, raw);
                    emitChoice(target, indent, out, raw, visited, referenced, source, depth);
                }
                case "MQOutput" -> {
                    diagnoseQueue(target, raw);
                    out.append(step(indent, target, "<to uri=\"jms:" + queue(target) + "\"/>"));
                }
                case "Input", "Output" -> {
                    // ввод/вывод подпотока — терминалы узла-ссылки, отдельного кода не создают:
                    // Input — точка входа разворачиваемого маршрута, Output — точка возврата
                    raw.add(new Diagnosis(target.id(), target.kind(), UnitStatus.CONVERTED,
                            target.kind().equals("Input")
                                    ? "вход подпотока (eflow:FCMSource) → from direct-вызова"
                                    : "выход подпотока (eflow:FCMSink) → возврат в родительский маршрут"));
                }
                case "Subflow" -> {
                    MsgFlow sub = resolveSubflow(target);
                    if (sub == null) {
                        raw.add(new Diagnosis(target.id(), target.kind(), UnitStatus.NOT_SUPPORTED,
                                "подпоток \"" + target.properties().getOrDefault("subflowName", "")
                                + "\" не найден во входном наборе — заглушка (FR-1.3, FR-2.7)"));
                        out.append(unknownNode(indent, target));
                    } else if (depth >= MAX_SUBFLOW_DEPTH) {
                        raw.add(new Diagnosis(target.id(), target.kind(), UnitStatus.PARTIAL,
                                "превышен лимит вложенности подпотоков (" + MAX_SUBFLOW_DEPTH
                                + "), возможно зацикливание — заглушка"));
                        out.append(unknownNode(indent, target));
                    } else {
                        String key = shortSubflowKey(target);
                        diagnoseFlowReference(target, sub, key, raw);
                        out.append(step(indent, target, "<to uri=\"direct:" + key + "\"/>"));
                        referenced.add(key);
                        walk(target, "out", indent, out, raw, visited, referenced, source, depth);
                    }
                }
                default -> {
                    raw.add(new Diagnosis(target.id(), target.kind(), UnitStatus.NOT_SUPPORTED,
                            "правило конвертации не определено — создана заглушка (FR-2.7)"));
                    out.append(unknownNode(indent, target));
                    walk(target, "out", indent, out, raw, visited, referenced, source, depth);
                }
            }
        });
    }

    /**
     * Резолюция подпотока: реестр проиндексирован и полным именем (например
     * "Schema_Check" для ссылки Schema_Check.subflow), и коротким ("Check").
     */
    private MsgFlow resolveSubflow(FlowNode reference) {
        String full = reference.properties().getOrDefault("subflowName", "");
        if (subflows.containsKey(full)) {
            return subflows.get(full);
        }
        int schemaSep = full.lastIndexOf('_');
        return schemaSep > 0 ? subflows.get(full.substring(schemaSep + 1)) : null;
    }

    /** Ключ direct-вызова: брокер-схема (префикс до «_») отбрасывается — реестр уже без неё. */
    private static String shortSubflowKey(FlowNode reference) {
        String full = reference.properties().getOrDefault("subflowName", "");
        int schemaSep = full.lastIndexOf('_');
        if (schemaSep < 0) {
            schemaSep = full.lastIndexOf('/');
        }
        return schemaSep < 0 ? full : full.substring(schemaSep + 1);
    }

    /** Диагноз узла-ссылки: подпоток развёрнут (FR-2.2: direct-вызов + вложенный маршрут). */
    private void diagnoseFlowReference(FlowNode reference, MsgFlow sub, String key, List<Diagnosis> raw) {
        raw.add(new Diagnosis(reference.id(), reference.kind(), UnitStatus.CONVERTED,
                "подпоток \"" + sub.name() + "\" развёрнут в маршрут direct:" + key
                + " (FR-2.2: вложенный <route id=\"" + routeId(sub.name()) + "\">)"));
    }

    /** Развёрнутый маршрут подпотока: from direct: + цепочка от каждого Input-узла. */
    private StringBuilder inlineRouteXml(MsgFlow sub) {
        StringBuilder out = new StringBuilder();
        String id = routeId(sub.name());
        out.append(line(1, "<!-- source: подпоток " + sub.name() + " -->"))
           .append(line(1, "<route id=\"" + id + "\">"))
           .append(line(2, "<from uri=\"direct:" + id + "\"/>"));

        StringBuilder steps = new StringBuilder();
        List<Diagnosis> ignored = new ArrayList<>(); // диагнозы подпотока собирает inlineResult()
        Set<String> visited = new LinkedHashSet<>();
        for (FlowNode entry : sub.nodes()) {
            if ("Input".equals(entry.kind())) {
                visited.add(entry.id());
                walk(entry, "out", 2, steps, ignored, visited, new LinkedHashSet<>(), sub, 1);
            }
        }
        out.append(steps).append(line(1, "</route>"));
        return out;
    }

    private void emitChoice(FlowNode route, int indent, StringBuilder out,
                            List<Diagnosis> raw, Set<String> visited, Set<String> referenced,
                            MsgFlow source, int depth) {
        out.append(line(indent, commentFor(route))).append(line(indent, "<choice>"));
        route.dynamicOutTerminals().stream()
                .filter(t -> !"Default".equals(t))
                .forEach(terminal -> out.append(line(indent + 1, "<when>"))
                        .append(line(indent + 2, "<simple>" + toSimple(route) + "</simple>"))
                        .append(branch(indent + 2, route, terminal, raw, visited, referenced, source, depth))
                        .append(line(indent + 1, "</when>")));
        connection(source, route.id(), "Default").ifPresent(link ->
                out.append(line(indent + 1, "<otherwise>"))
                   .append(branch(indent + 2, route, "Default", raw, visited, referenced, source, depth))
                   .append(line(indent + 1, "</otherwise>")));
        out.append(line(indent, "</choice>"));
    }

    private StringBuilder branch(int indent, FlowNode route, String terminal, List<Diagnosis> raw,
                                 Set<String> visited, Set<String> referenced, MsgFlow source, int depth) {
        StringBuilder out = new StringBuilder();
        walk(route, terminal, indent, out, raw, visited, referenced, source, depth);
        return out;
    }

    private StringBuilder catchBranch(FlowNode input, List<Diagnosis> raw, Set<String> visited) {
        StringBuilder out = new StringBuilder();
        for (String terminal : new String[] {"catch", "failure"}) {
            Optional<FlowConnection> link = connection(flow, input.id(), terminal);
            if (link.isPresent()) {
                FlowNode target = flow.node(link.get().targetNodeId());
                visited.add(target.id());
                diagnoseQueue(target, raw);
                raw.add(new Diagnosis(target.id(), target.kind(), UnitStatus.CONVERTED,
                        "обработчик catch/fail → doCatch"));
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

    /**
     * Диагнозы узлов, не достигнутых при обходе: конвертер не молчит о них (FR-5.2).
     * Причина передаётся вызывающим контекстом (родительский поток / подпоток).
     */
    private List<Diagnosis> orphanDiagnoses(MsgFlow source, Set<String> visited, boolean anyEntry,
                                            String notReachedReason, String noEntryReason) {
        String reason = anyEntry ? notReachedReason : noEntryReason;
        return source.nodes().stream()
                .filter(n -> !visited.contains(n.id()))
                .map(n -> new Diagnosis(n.id(), n.kind(),
                        SUPPORTED_BASE_KINDS.contains(n.kind()) ? UnitStatus.PARTIAL : UnitStatus.NOT_SUPPORTED,
                        reason))
                .toList();
    }

    private void diagnoseInput(FlowNode input, List<Diagnosis> raw) {
        String queue = input.properties().getOrDefault("queueName", "");
        if (queue.isBlank()) {
            raw.add(new Diagnosis(input.id(), input.kind(), UnitStatus.PARTIAL,
                    "property queueName не задана — заглушка TODO-queue-not-set"));
        } else {
            raw.add(new Diagnosis(input.id(), input.kind(), UnitStatus.CONVERTED,
                    "вход: jms:" + queue));
        }
    }

    private void diagnoseCompute(FlowNode node, List<Diagnosis> raw) {
        if (node.properties().getOrDefault("computeExpression", "").isBlank()) {
            raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.PARTIAL,
                    "computeExpression не задан — бин bean:" + beanId(node)
                    + " резервируется по id узла и может не существовать"));
        } else {
            raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.CONVERTED,
                    "bean:" + beanId(node)));
        }
    }

    private void diagnoseRoute(FlowNode route, List<Diagnosis> raw) {
        String pattern = route.properties().getOrDefault("filterTable.filterPattern", "");
        long when = route.dynamicOutTerminals().stream().filter(t -> !"Default".equals(t)).count();
        String branches = when == 1 ? "1 ветвь" : when + " ветвей";
        String note = branches + (connection(flow, route.id(), "Default").isPresent()
                ? " + otherwise" : "") + "; фильтр → simple";
        if (pattern.isBlank()) {
            raw.add(new Diagnosis(route.id(), route.kind(), UnitStatus.PARTIAL,
                    "filterPattern не задан — simple: TODO. " + note));
        } else {
            raw.add(new Diagnosis(route.id(), route.kind(), UnitStatus.CONVERTED, note));
        }
    }

    private void diagnoseQueue(FlowNode node, List<Diagnosis> raw) {
        String queue = node.properties().getOrDefault("queueName", "");
        if (queue.isBlank()) {
            raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.PARTIAL,
                    "queueName не задана — заглушка TODO-queue-not-set"));
        } else {
            raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.CONVERTED,
                    "jms:" + queue));
        }
    }

    /** Несколько диагнозов одного узла → одна запись: худший статус + объединённые примечания. */
    private List<Diagnosis> canonical(List<Diagnosis> raw) {
        Map<String, Diagnosis> merged = new LinkedHashMap<>();
        for (Diagnosis d : raw) {
            Diagnosis exist = merged.get(d.nodeId());
            if (exist == null) {
                merged.put(d.nodeId(), d);
            } else {
                UnitStatus worst = UnitStatus.worst(exist.status(), d.status());
                String note = exist.note().equals(d.note())
                        ? exist.note()
                        : exist.note() + "; " + d.note();
                merged.put(d.nodeId(), new Diagnosis(d.nodeId(), exist.kind(), worst, note));
            }
        }
        return List.copyOf(merged.values());
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

    private Optional<FlowConnection> connection(MsgFlow source, String nodeId, String terminal) {
        return source.connections().stream()
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
        // значение в апострофах (ESQL-константа символьного типа) сравнивается в simple через ==;
        // числовые литералы ESQL в msgflow-фильтрах всегда без апострофов → >
        String op = value.startsWith("'") ? "== " : "> ";
        return camelPath(path) + "} " + op + value;
    }

    private static String camelPath(String path) {
        String[] segments = path.replaceFirst("^\\$Body/", "").split("/");
        StringBuilder body = new StringBuilder("${body");
        for (int i = 1; i < segments.length; i++) { // первый сегмент = корень модели
            body.append('.').append(lowerFirst(segments[i]));
        }
        return body.toString();
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

    /** Идентификатор маршрута/ключ direct: имя IR без расширения потока или подпотока. */
    private static String routeId(String flowName) {
        return flowName.replaceAll("\\.(msgflow|subflow)$", "");
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
