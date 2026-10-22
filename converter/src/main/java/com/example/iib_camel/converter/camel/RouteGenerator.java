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
import java.util.Set;

public final class RouteGenerator {
    private static final String CAMEL_NS = "http://camel.apache.org/schema/spring";
    public static final Set<String> SUPPORTED_BASE_KINDS =
            Set.of("MQInput", "MQOutput", "Compute", "Route", "Subflow", "Input", "Output");

    private final MsgFlow flow;
    private final SubflowResolver resolver;
    private final Set<String> emittedSubflows;

    public RouteGenerator(MsgFlow flow) {
        this(flow, Map.of());
    }

    public RouteGenerator(MsgFlow flow, Map<String, MsgFlow> subflows) {
        this(flow, subflows, null);
    }

    public RouteGenerator(MsgFlow flow, Map<String, MsgFlow> subflows, Set<String> emittedSubflows) {
        this.flow = flow;
        this.resolver = new SubflowResolver(subflows);
        this.emittedSubflows = emittedSubflows;
    }

    public RouteResult generate() {
        var out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<routes xmlns=\"").append(CAMEL_NS).append("\">\n");
        List<Diagnosis> raw = validate(flow, false, new LinkedHashSet<>());
        Set<String> visited = new LinkedHashSet<>();
        Map<String, MsgFlow> referenced = new LinkedHashMap<>();
        List<FlowNode> inputs = nodes(flow, "MQInput");
        for (FlowNode input : inputs) {
            visited.add(input.id());
            diagnoseInput(input, raw);
            String id = routeId(flow.name()) + (inputs.size() > 1 ? "-" + input.id() : "");
            out.append(line(1, commentFor(input)))
               .append(line(1, "<route id=\"" + xml(id) + "\">"))
               .append(line(2, "<from uri=\"jms:" + xml(queue(input)) + "\"/>"));
            if (!rawValidationErrors(raw)) {
                boolean catches = connected(flow, input, "catch");
                if (catches) out.append(snapshot(2, "capture")).append(line(2, "<doTry>"));
                out.append(branch(catches ? 3 : 2, input, "out", raw, visited, referenced, flow));
                if (catches) {
                    emitCatch(input, "catch", 3, out, raw, visited, referenced, flow);
                    out.append(releaseSnapshot(3)).append(line(2, "</doTry>"));
                }
            } else {
                out.append(unsupported(2, input, validationMessage(raw)));
            }
            out.append(line(1, "</route>"));
        }
        if (!rawValidationErrors(raw)) {
            emitSubflows(out, referenced);
        }
        raw.addAll(orphanDiagnoses(flow, visited, !inputs.isEmpty(),
                "нет пути от входного узла MQInput — узел не попал в маршрут",
                "в потоке нет входного узла MQInput — маршрут не сгенерирован",
                rawValidationErrors(raw)));
        out.append("</routes>\n");
        return new RouteResult(out.toString(), canonical(raw));
    }

    public RouteResult inlineResult() {
        List<Diagnosis> raw = validate(flow, true, new LinkedHashSet<>());
        Set<String> visited = new LinkedHashSet<>();
        List<FlowNode> entries = nodes(flow, "Input");
        if (!rawValidationErrors(raw)) {
            for (FlowNode entry : entries) {
                visited.add(entry.id());
                diagnoseBoundary(entry, raw);
                walk(entry, "out", 2, new StringBuilder(), raw, visited, new LinkedHashMap<>(), flow);
            }
        }
        raw.addAll(orphanDiagnoses(flow, visited, !entries.isEmpty(),
                "нет пути от Input-узла внутри подпотока — узел не попал в разворачиваемый маршрут",
                "в подпотоке нет узла Input (eflow:FCMSource) — содержимое не разворачивается",
                rawValidationErrors(raw)));
        return new RouteResult("", canonical(raw));
    }

    private void emitSubflows(StringBuilder out, Map<String, MsgFlow> referenced) {
        Set<String> traversed = new LinkedHashSet<>();
        Set<String> emitted = emittedSubflows == null ? new LinkedHashSet<>() : emittedSubflows;
        while (true) {
            var pending = referenced.entrySet().stream().filter(e -> !traversed.contains(e.getKey()))
                    .findFirst().orElse(null);
            if (pending == null) return;
            String id = pending.getKey();
            MsgFlow sub = pending.getValue();
            traversed.add(id);
            if (!emitted.add(id)) continue;
            out.append(line(1, "<!-- source: подпоток " + commentText(sub.name()) + " -->"))
               .append(line(1, "<route id=\"" + xml(id) + "\">"))
               .append(line(2, "<from uri=\"direct:" + xml(id) + "\"/>"));
            FlowNode input = nodes(sub, "Input").getFirst();
            out.append(branch(2, input, "out", new ArrayList<>(), new LinkedHashSet<>(), referenced, sub));
            out.append(line(1, "</route>"));
        }
    }

    private void walk(FlowNode node, String terminal, int indent, StringBuilder out,
                      List<Diagnosis> raw, Set<String> visited, Map<String, MsgFlow> referenced,
                      MsgFlow source) {
        List<FlowConnection> links = connections(source, node.id(), terminal);
        if (links.size() > 1) {
            // IIB fan-out shares the input message, not mutations from a preceding branch.
            out.append(line(indent, "<multicast stopOnException=\"true\" shareUnitOfWork=\"true\">"));
            for (FlowConnection link : links) {
                out.append(line(indent + 1, "<pipeline>"));
                emitNode(source.node(link.targetNodeId()), indent + 2, out, raw, visited, referenced, source);
                out.append(line(indent + 1, "</pipeline>"));
            }
            out.append(line(indent, "</multicast>"));
        } else if (!links.isEmpty()) {
            emitNode(source.node(links.getFirst().targetNodeId()), indent, out, raw, visited, referenced, source);
        }
    }

    private void emitNode(FlowNode node, int indent, StringBuilder out, List<Diagnosis> raw,
                          Set<String> visited, Map<String, MsgFlow> referenced, MsgFlow source) {
        visited.add(node.id());
        switch (node.kind()) {
            case "Compute" -> {
                diagnoseCompute(node, raw);
                if (connected(source, node, "failure")) {
                    String success = property(source, node, "success");
                    out.append(line(indent, "<removeProperty name=\"" + success + "\"/>"))
                       .append(snapshot(indent, "capture"))
                       .append(line(indent, "<doTry>"))
                       .append(step(indent + 1, node, "<to uri=\"bean:" + xml(beanId(node)) + "\"/>"))
                       .append(setProperty(indent + 1, success, "<constant>true</constant>"));
                    emitCatch(node, "failure", indent + 1, out, raw, visited, referenced, source);
                    out.append(releaseSnapshot(indent + 1)).append(line(indent, "</doTry>"))
                       .append(line(indent, "<filter>"))
                       .append(line(indent + 1, exchangeProperty(success)));
                    out.append(branch(indent + 1, node, "out", raw, visited, referenced, source));
                    out.append(line(indent, "</filter>"))
                       .append(line(indent, "<removeProperty name=\"" + success + "\"/>"));
                } else {
                    out.append(step(indent, node, "<to uri=\"bean:" + xml(beanId(node)) + "\"/>"));
                    walk(node, "out", indent, out, raw, visited, referenced, source);
                }
            }
            case "Route" -> emitRouting(node, indent, out, raw, visited, referenced, source);
            case "MQOutput" -> {
                diagnoseQueue(node, raw);
                out.append(step(indent, node, "<to uri=\"jms:" + xml(queue(node)) + "\"/>"));
                walk(node, "out", indent, out, raw, visited, referenced, source);
            }
            case "Input" -> {
                diagnoseBoundary(node, raw);
                walk(node, "out", indent, out, raw, visited, referenced, source);
            }
            case "Output" -> {
                diagnoseBoundary(node, raw);
                out.append(setProperty(indent, completion(source), "<constant>true</constant>"));
            }
            case "Subflow" -> emitSubflowReference(node, indent, out, raw, visited, referenced, source);
            default -> {
                raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.NOT_SUPPORTED,
                        "правило конвертации не определено — создана заглушка (FR-2.7)"));
                out.append(unknownNode(indent, node));
                walk(node, "out", indent, out, raw, visited, referenced, source);
            }
        }
    }

    private void emitSubflowReference(FlowNode node, int indent, StringBuilder out, List<Diagnosis> raw,
                                      Set<String> visited, Map<String, MsgFlow> referenced, MsgFlow source) {
        MsgFlow sub = resolver.resolve(node);
        if (sub == null) {
            raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.NOT_SUPPORTED,
                    "подпоток \"" + node.properties().getOrDefault("subflowName", "")
                            + "\" не найден во входном наборе — заглушка (FR-1.3, FR-2.7)"));
            out.append(unknownNode(indent, node));
            walk(node, "out", indent, out, raw, visited, referenced, source);
            return;
        }
        String key = routeId(sub.name());
        raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.CONVERTED,
                "подпоток \"" + sub.name() + "\" → direct:" + key));
        referenced.putIfAbsent(key, sub);
        out.append(line(indent, "<removeProperty name=\"" + completion(sub) + "\"/>"))
           .append(step(indent, node, "<to uri=\"direct:" + xml(key) + "\"/>"))
           .append(line(indent, "<filter>"))
           .append(line(indent + 1, exchangeProperty(completion(sub))));
        StringBuilder continuation = branch(indent + 1, node, "out", raw, visited, referenced, source);
        out.append(continuation).append(line(indent, "</filter>"))
           .append(line(indent, "<removeProperty name=\"" + completion(sub) + "\"/>"));
    }

    private void emitRouting(FlowNode route, int indent, StringBuilder out, List<Diagnosis> raw,
                             Set<String> visited, Map<String, MsgFlow> referenced, MsgFlow source) {
        List<String> predicates = new ArrayList<>();
        String mode = route.properties().getOrDefault("distributionMode", "All");
        try {
            if (!mode.equalsIgnoreCase("First") && !mode.equalsIgnoreCase("All")) {
                throw new IllegalArgumentException("неподдерживаемый distributionMode: " + mode);
            }
            if (route.routeRules().isEmpty()) throw new IllegalArgumentException("filterTable не задана");
            for (var rule : route.routeRules()) {
                if (rule.outputTerminal().isBlank() || rule.outputTerminal().equalsIgnoreCase("Default")
                        || rule.outputTerminal().equalsIgnoreCase("Failure")) {
                    throw new IllegalArgumentException("некорректный routingOutputTerminal: " + rule.outputTerminal());
                }
                predicates.add(IibXPath.translate(rule.filterPattern()));
            }
        } catch (IllegalArgumentException e) {
            raw.add(new Diagnosis(route.id(), route.kind(), UnitStatus.NOT_SUPPORTED, e.getMessage()));
            out.append(line(indent, commentFor(route))).append(unsupported(indent, route, e.getMessage()));
            return;
        }
        raw.add(new Diagnosis(route.id(), route.kind(), UnitStatus.CONVERTED,
                route.routeRules().size() + " правил → XPath; distributionMode=" + mode));
        out.append(line(indent, commentFor(route)));
        if (connected(source, route, "Failure")) {
            emitRouteWithFailure(route, predicates, mode, indent, out, raw, visited, referenced, source);
        } else {
            emitRouteBranches(route, predicates.stream().map(RouteGenerator::xpath).toList(),
                    mode, indent, out, raw, visited, referenced, source, false);
        }
    }

    private void emitRouteWithFailure(FlowNode route, List<String> predicates, String mode, int indent,
                                       StringBuilder out, List<Diagnosis> raw, Set<String> visited,
                                       Map<String, MsgFlow> referenced, MsgFlow source) {
        if (mode.equalsIgnoreCase("All")) {
            emitAllWithFailure(route, predicates, indent, out, raw, visited, referenced, source);
            return;
        }
        String success = property(source, route, "success");
        String matched = property(source, route, "matched");
        out.append(line(indent, "<removeProperty name=\"" + success + "\"/>"))
           .append(setProperty(indent, matched, "<constant>false</constant>"))
           .append(snapshot(indent, "capture"))
           .append(line(indent, "<doTry>"));
        for (int i = 0; i < predicates.size(); i++) {
            String match = property(source, route, "rule" + i);
            out.append(line(indent + 1, "<removeProperty name=\"" + match + "\"/>"));
            if (mode.equalsIgnoreCase("First")) {
                out.append(line(indent + 1, "<filter>"))
                   .append(line(indent + 2, "<simple>${exchangeProperty." + matched + "} != true</simple>"));
            }
            int level = mode.equalsIgnoreCase("First") ? indent + 2 : indent + 1;
            out.append(setProperty(level, match,
                    "<xpath resultType=\"java.lang.Boolean\">" + xml(predicates.get(i)) + "</xpath>"))
               .append(line(level, "<filter>"))
               .append(line(level + 1, exchangeProperty(match)))
               .append(setProperty(level + 1, matched, "<constant>true</constant>"))
               .append(line(level, "</filter>"));
            if (mode.equalsIgnoreCase("First")) out.append(line(indent + 1, "</filter>"));
        }
        out.append(setProperty(indent + 1, success, "<constant>true</constant>"));
        emitCatch(route, "Failure", indent + 1, out, raw, visited, referenced, source);
        out.append(releaseSnapshot(indent + 1)).append(line(indent, "</doTry>"))
           .append(line(indent, "<filter>"))
           .append(line(indent + 1, exchangeProperty(success)));
        List<String> expressions = new ArrayList<>();
        for (int i = 0; i < predicates.size(); i++) {
            expressions.add(exchangeProperty(property(source, route, "rule" + i)));
        }
        emitRouteBranches(route, expressions, mode, indent + 1, out, raw, visited, referenced, source, true);
        out.append(line(indent, "</filter>"));
        for (String suffix : List.of("success", "matched")) {
            out.append(line(indent, "<removeProperty name=\"" + property(source, route, suffix) + "\"/>"));
        }
        for (int i = 0; i < predicates.size(); i++) {
            out.append(line(indent, "<removeProperty name=\"" + property(source, route, "rule" + i) + "\"/>"));
        }
    }

    private void emitAllWithFailure(FlowNode route, List<String> predicates, int indent,
                                     StringBuilder out, List<Diagnosis> raw, Set<String> visited,
                                     Map<String, MsgFlow> referenced, MsgFlow source) {
        String active = property(source, route, "active");
        String matched = property(source, route, "matched");
        String rule = property(source, route, "rule");
        out.append(setProperty(indent, active, "<constant>true</constant>"))
           .append(setProperty(indent, matched, "<constant>false</constant>"));
        for (int i = 0; i < predicates.size(); i++) {
            out.append(line(indent, "<filter>"))
               .append(line(indent + 1, exchangeProperty(active)))
               .append(snapshot(indent + 1, "capture"))
               .append(line(indent + 1, "<doTry>"))
               .append(setProperty(indent + 2, rule,
                       "<xpath resultType=\"java.lang.Boolean\">" + xml(predicates.get(i)) + "</xpath>"))
               .append(line(indent + 2, "<doCatch>"))
               .append(line(indent + 3, "<exception>java.lang.Exception</exception>"))
               .append(snapshot(indent + 3, "restore"))
               .append(line(indent + 3, "<removeProperty name=\"" + active + "\"/>"))
               .append(branch(indent + 3, route, "Failure", raw, visited, referenced, source))
               .append(line(indent + 2, "</doCatch>"))
               .append(releaseSnapshot(indent + 2))
               .append(line(indent + 1, "</doTry>"))
               .append(line(indent + 1, "<filter>"))
               .append(line(indent + 2, exchangeProperty(active)))
               .append(line(indent + 2, "<filter>"))
               .append(line(indent + 3, exchangeProperty(rule)))
               .append(setProperty(indent + 3, matched, "<constant>true</constant>"))
               .append(line(indent + 3, "<multicast stopOnException=\"true\" shareUnitOfWork=\"true\">"))
               .append(line(indent + 4, "<pipeline>"))
               .append(branch(indent + 5, route, route.routeRules().get(i).outputTerminal(), raw, visited, referenced, source))
               .append(line(indent + 4, "</pipeline>"))
               .append(line(indent + 4, "<pipeline>"))
               .append(line(indent + 5, "<log loggingLevel=\"TRACE\" message=\"Preserve IIB Route input for the next rule\"/>"))
               .append(line(indent + 4, "</pipeline>"))
               .append(line(indent + 3, "</multicast>"))
               .append(line(indent + 2, "</filter>"))
               .append(line(indent + 1, "</filter>"))
               .append(line(indent, "</filter>"));
        }
        if (connected(source, route, "Default")) {
            out.append(line(indent, "<filter>"))
               .append(line(indent + 1, exchangeProperty(active)))
               .append(line(indent + 1, "<filter>"))
               .append(line(indent + 2, "<simple>${exchangeProperty." + matched + "} != true</simple>"))
               .append(branch(indent + 2, route, "Default", raw, visited, referenced, source))
               .append(line(indent + 1, "</filter>"))
               .append(line(indent, "</filter>"));
        }
        for (String key : List.of(active, matched, rule)) {
            out.append(line(indent, "<removeProperty name=\"" + key + "\"/>"));
        }
    }

    private void emitRouteBranches(FlowNode route, List<String> expressions, String mode, int indent,
                                    StringBuilder out, List<Diagnosis> raw, Set<String> visited,
                                    Map<String, MsgFlow> referenced, MsgFlow source, boolean staged) {
        if (mode.equalsIgnoreCase("First") || expressions.size() == 1) {
            out.append(line(indent, "<choice>"));
            for (int i = 0; i < expressions.size(); i++) {
                out.append(line(indent + 1, "<when>"))
                   .append(line(indent + 2, expressions.get(i)))
                   .append(branch(indent + 2, route, route.routeRules().get(i).outputTerminal(), raw,
                           visited, referenced, source))
                   .append(line(indent + 1, "</when>"));
            }
            if (connected(source, route, "Default")) {
                out.append(line(indent + 1, "<otherwise>"))
                   .append(branch(indent + 2, route, "Default", raw, visited, referenced, source))
                   .append(line(indent + 1, "</otherwise>"));
            }
            out.append(line(indent, "</choice>"));
        } else {
            out.append(line(indent, "<multicast stopOnException=\"true\" shareUnitOfWork=\"true\">"));
            for (int i = 0; i < expressions.size(); i++) {
                emitFilteredBranch(route, route.routeRules().get(i).outputTerminal(), expressions.get(i),
                        indent + 1, out, raw, visited, referenced, source);
            }
            if (connected(source, route, "Default")) {
                String condition;
                if (staged) {
                    condition = "<simple>${exchangeProperty." + property(source, route, "matched") + "} != true</simple>";
                } else {
                    String anyMatch = route.routeRules().stream().map(r -> "(" + IibXPath.translate(r.filterPattern()) + ")")
                            .collect(java.util.stream.Collectors.joining(" or "));
                    condition = xpath("not(" + anyMatch + ")");
                }
                emitFilteredBranch(route, "Default", condition, indent + 1, out, raw, visited, referenced, source);
            }
            out.append(line(indent, "</multicast>"));
        }
    }

    private void emitFilteredBranch(FlowNode route, String terminal, String expression, int indent,
                                    StringBuilder out, List<Diagnosis> raw, Set<String> visited,
                                    Map<String, MsgFlow> referenced, MsgFlow source) {
        out.append(line(indent, "<pipeline>"))
           .append(line(indent + 1, "<filter>"))
           .append(line(indent + 2, expression))
           .append(branch(indent + 2, route, terminal, raw, visited, referenced, source))
           .append(line(indent + 1, "</filter>"))
           .append(line(indent, "</pipeline>"));
    }

    private StringBuilder branch(int indent, FlowNode node, String terminal, List<Diagnosis> raw,
                                 Set<String> visited, Map<String, MsgFlow> referenced, MsgFlow source) {
        StringBuilder out = new StringBuilder();
        walk(node, terminal, indent, out, raw, visited, referenced, source);
        if (out.isEmpty()) out.append(line(indent, "<log loggingLevel=\"TRACE\" message=\"IIB terminal is unconnected\"/>"));
        return out;
    }

    private void emitCatch(FlowNode node, String terminal, int indent, StringBuilder out,
                           List<Diagnosis> raw, Set<String> visited, Map<String, MsgFlow> referenced, MsgFlow source) {
        out.append(line(indent, "<doCatch>"))
           .append(line(indent + 1, "<exception>java.lang.Exception</exception>"))
           .append(snapshot(indent + 1, "restore"))
           .append(branch(indent + 1, node, terminal, raw, visited, referenced, source))
           .append(line(indent, "</doCatch>"));
    }

    private List<Diagnosis> validate(MsgFlow source, boolean subflow, Set<String> path) {
        List<Diagnosis> errors = new ArrayList<>();
        String name = routeId(source.name());
        if (!path.add(name)) return errors;
        if (subflow) {
            if (nodes(source, "Input").size() != 1 || nodes(source, "Output").size() != 1) {
                String reason = "подпоток требует ровно один Input и один Output; несколько терминалов пока не поддерживаются";
                if (source.nodes().isEmpty()) errors.add(new Diagnosis(name, "Subflow", UnitStatus.NOT_SUPPORTED,
                        "граф не преобразован: " + reason));
                else source.nodes().forEach(n -> errors.add(validation(n, reason)));
            }
            for (FlowNode node : source.nodes()) {
                boolean fanOut = source.connections().stream().filter(c -> c.sourceNodeId().equals(node.id()))
                        .map(FlowConnection::sourceTerminal).distinct()
                        .anyMatch(t -> connections(source, node.id(), t).size() > 1);
                if (fanOut || (node.kind().equals("Route") && node.routeRules().size() > 1
                        && !node.properties().getOrDefault("distributionMode", "All").equalsIgnoreCase("First"))) {
                    errors.add(validation(node, "многократная пропагация внутри подпотока пока не поддерживается"));
                }
            }
        }
        for (FlowConnection link : source.connections()) {
            FlowNode from = source.node(link.sourceNodeId());
            FlowNode target = source.node(link.targetNodeId());
            if (!link.targetTerminal().equalsIgnoreCase("in")) {
                errors.add(validation(target, "неподдерживаемый входной терминал: " + link.targetTerminal()));
            }
            boolean known = switch (from.kind()) {
                case "MQInput" -> Set.of("out", "catch").contains(link.sourceTerminal().toLowerCase(java.util.Locale.ROOT));
                case "Compute" -> Set.of("out", "failure").contains(link.sourceTerminal().toLowerCase(java.util.Locale.ROOT));
                case "Route" -> link.sourceTerminal().equalsIgnoreCase("Default")
                        || link.sourceTerminal().equalsIgnoreCase("Failure")
                        || from.dynamicOutTerminals().contains(link.sourceTerminal())
                        || from.routeRules().stream().anyMatch(r -> r.outputTerminal().equals(link.sourceTerminal()));
                case "Output" -> false;
                default -> link.sourceTerminal().equalsIgnoreCase("out");
            };
            if (!known) {
                String message = from.kind().equals("MQInput") && link.sourceTerminal().equalsIgnoreCase("failure")
                        ? "MQInput.failure относится к ошибкам чтения транспорта; doCatch не воспроизводит эту семантику"
                        : "неподдерживаемый выходной терминал: " + link.sourceTerminal();
                errors.add(validation(from, message));
            }
        }
        Map<String, Integer> state = new LinkedHashMap<>();
        for (FlowNode node : source.nodes()) detectCycle(source, node, state, new ArrayList<>(), errors);
        for (FlowNode node : source.nodes()) {
            if (!node.kind().equals("Subflow")) continue;
            MsgFlow sub;
            try {
                sub = resolver.resolve(node);
            } catch (IllegalArgumentException e) {
                errors.add(validation(node, e.getMessage()));
                continue;
            }
            if (sub == null) continue;
            String key = routeId(sub.name());
            if (path.contains(key)) {
                errors.add(validation(node, "цикл подпотоков: " + String.join(" → ", path) + " → " + key));
                continue;
            }
            List<Diagnosis> nested = validate(sub, true, new LinkedHashSet<>(path));
            if (!nested.isEmpty()) {
                errors.add(validation(node, "подпоток " + key + ": " + validationMessage(nested)));
            }
        }
        return errors;
    }

    private static void detectCycle(MsgFlow source, FlowNode node, Map<String, Integer> state,
                                    List<String> path, List<Diagnosis> errors) {
        if (state.getOrDefault(node.id(), 0) == 2) return;
        if (state.getOrDefault(node.id(), 0) == 1) {
            int index = path.indexOf(node.id());
            var cycle = new ArrayList<>(path.subList(index, path.size()));
            cycle.add(node.id());
            errors.add(validation(node, "цикл графа " + source.name() + ": " + String.join(" → ", cycle)));
            return;
        }
        state.put(node.id(), 1);
        path.add(node.id());
        for (FlowConnection link : source.connections()) {
            if (link.sourceNodeId().equals(node.id())) detectCycle(source, source.node(link.targetNodeId()), state, path, errors);
        }
        path.removeLast();
        state.put(node.id(), 2);
    }

    private static Diagnosis validation(FlowNode node, String message) {
        return new Diagnosis(node.id(), node.kind(), UnitStatus.NOT_SUPPORTED, "граф не преобразован: " + message);
    }

    private static boolean rawValidationErrors(List<Diagnosis> raw) {
        return raw.stream().anyMatch(d -> d.note().startsWith("граф не преобразован:"));
    }

    private static String validationMessage(List<Diagnosis> raw) {
        return raw.stream().filter(d -> d.status() == UnitStatus.NOT_SUPPORTED).map(Diagnosis::note)
                .distinct().collect(java.util.stream.Collectors.joining("; "));
    }

    private List<Diagnosis> orphanDiagnoses(MsgFlow source, Set<String> visited, boolean anyEntry,
                                           String notReachedReason, String noEntryReason, boolean blocked) {
        String reason = blocked ? "генерация маршрута заблокирована ошибкой графа" : anyEntry ? notReachedReason : noEntryReason;
        return source.nodes().stream().filter(n -> !visited.contains(n.id()))
                .map(n -> new Diagnosis(n.id(), n.kind(),
                        SUPPORTED_BASE_KINDS.contains(n.kind()) ? UnitStatus.PARTIAL : UnitStatus.NOT_SUPPORTED, reason))
                .toList();
    }

    private void diagnoseInput(FlowNode input, List<Diagnosis> raw) {
        String queue = input.properties().getOrDefault("queueName", "");
        raw.add(new Diagnosis(input.id(), input.kind(), queue.isBlank() ? UnitStatus.PARTIAL : UnitStatus.CONVERTED,
                queue.isBlank() ? "property queueName не задана — заглушка TODO-queue-not-set" : "вход: jms:" + queue));
    }

    private void diagnoseCompute(FlowNode node, List<Diagnosis> raw) {
        boolean missing = node.properties().getOrDefault("computeExpression", "").isBlank();
        raw.add(new Diagnosis(node.id(), node.kind(), missing ? UnitStatus.PARTIAL : UnitStatus.CONVERTED,
                missing ? "computeExpression не задан — бин bean:" + beanId(node) + " резервируется по id узла и может не существовать"
                        : "bean:" + beanId(node)));
    }

    private void diagnoseQueue(FlowNode node, List<Diagnosis> raw) {
        String queue = node.properties().getOrDefault("queueName", "");
        raw.add(new Diagnosis(node.id(), node.kind(), queue.isBlank() ? UnitStatus.PARTIAL : UnitStatus.CONVERTED,
                queue.isBlank() ? "queueName не задана — заглушка TODO-queue-not-set" : "jms:" + queue));
    }

    private void diagnoseBoundary(FlowNode node, List<Diagnosis> raw) {
        raw.add(new Diagnosis(node.id(), node.kind(), UnitStatus.CONVERTED,
                node.kind().equals("Input") ? "вход подпотока → direct-вызов" : "выход подпотока → продолжение родителя"));
    }

    private List<Diagnosis> canonical(List<Diagnosis> raw) {
        Map<String, Diagnosis> merged = new LinkedHashMap<>();
        for (Diagnosis d : raw) {
            Diagnosis existing = merged.get(d.nodeId());
            if (existing == null) merged.put(d.nodeId(), d);
            else {
                String note = existing.note().equals(d.note()) ? existing.note() : existing.note() + "; " + d.note();
                merged.put(d.nodeId(), new Diagnosis(d.nodeId(), existing.kind(), UnitStatus.worst(existing.status(), d.status()), note));
            }
        }
        return List.copyOf(merged.values());
    }

    private static List<FlowNode> nodes(MsgFlow source, String kind) {
        return source.nodes().stream().filter(n -> n.kind().equals(kind)).toList();
    }

    private static List<FlowConnection> connections(MsgFlow source, String nodeId, String terminal) {
        boolean reserved = !source.node(nodeId).kind().equals("Route") || terminal.equalsIgnoreCase("failure")
                || terminal.equalsIgnoreCase("default");
        return source.connections().stream().filter(c -> c.sourceNodeId().equals(nodeId)
                && (c.sourceTerminal().equals(terminal) || reserved && c.sourceTerminal().equalsIgnoreCase(terminal))).toList();
    }

    private static boolean connected(MsgFlow source, FlowNode node, String terminal) {
        return !connections(source, node.id(), terminal).isEmpty();
    }

    static String beanId(FlowNode compute) {
        String expression = compute.properties().getOrDefault("computeExpression", "");
        int hash = expression.indexOf('#');
        String module = hash < 0 ? compute.id() : expression.substring(hash + 1).split("\\.", 2)[0];
        return camelize(module);
    }

    static String camelize(String s) {
        var parts = s.split("[_-]", -1);
        var result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            result.append(result.isEmpty() ? Character.toLowerCase(part.charAt(0)) : Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1));
        }
        return result.toString();
    }

    private static String property(MsgFlow source, FlowNode node, String suffix) {
        String key = source.name() + ":" + node.id();
        return "iib_" + java.util.HexFormat.of().formatHex(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "_" + suffix;
    }

    private static String completion(MsgFlow source) {
        return "iib_complete_" + java.util.HexFormat.of().formatHex(source.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String setProperty(int indent, String name, String expression) {
        return line(indent, "<setProperty name=\"" + name + "\">") + line(indent + 1, expression) + line(indent, "</setProperty>");
    }

    private static String snapshot(int indent, String method) {
        return line(indent, "<bean beanType=\"com.example.iib_camel.runtime.compute.MessageSnapshots\" method=\"" + method + "\"/>");
    }

    private static String releaseSnapshot(int indent) {
        return line(indent, "<doFinally>") + snapshot(indent + 1, "release") + line(indent, "</doFinally>");
    }

    private static String exchangeProperty(String name) {
        return "<exchangeProperty>" + xml(name) + "</exchangeProperty>";
    }

    private static String xpath(String predicate) {
        return "<xpath>" + xml(predicate) + "</xpath>";
    }

    private static String step(int indent, FlowNode node, String element) {
        return line(indent, commentFor(node)) + line(indent, element);
    }

    private static String unsupported(int indent, FlowNode node, String reason) {
        return line(indent, "<throwException exceptionType=\"java.lang.UnsupportedOperationException\" message=\""
                + xml("Unsupported IIB " + node.kind() + " " + node.id() + ": " + reason) + "\"/>");
    }

    private static String unknownNode(int indent, FlowNode node) {
        return line(indent, "<!-- TODO (FR-2.7): правило конвертации узла \"" + commentText(node.kind()) + "\" не определено -->")
                + line(indent, "<log message=\"Unsupported IIB node: " + xml(node.kind()) + "\"/>");
    }

    private static String queue(FlowNode mq) {
        return mq.properties().getOrDefault("queueName", "TODO-queue-not-set");
    }

    private static String routeId(String flowName) {
        return SubflowResolver.name(flowName);
    }

    private static String commentFor(FlowNode node) {
        return "<!-- source: узел " + commentText(node.id()) + " (" + commentText(node.kind()) + ") -->";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String commentText(String value) {
        String escaped = xml(value);
        while (escaped.contains("--")) escaped = escaped.replace("--", "- -");
        return escaped;
    }

    private static String line(int indent, String text) {
        return "  ".repeat(indent) + text + "\n";
    }
}
