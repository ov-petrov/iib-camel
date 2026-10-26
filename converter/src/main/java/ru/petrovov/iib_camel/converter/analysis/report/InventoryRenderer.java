package ru.petrovov.iib_camel.converter.analysis.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.Role;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Diagnostic;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.EndpointUsage;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.FlowAnalysis;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.FlowLink;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.GraphEdge;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.HandlerReference;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.LinkConfidence;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.NodeInstance;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Reachability;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Severity;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.SubflowInstance;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;
import ru.petrovov.iib_camel.converter.diagram.MermaidEscaper;
import ru.petrovov.iib_camel.converter.diagram.Orientation;
import tools.jackson.databind.json.JsonMapper;

public final class InventoryRenderer {

    private static final int MAX_OVERVIEW_NODES = 200;
    private static final int MAX_OVERVIEW_EDGES = 400;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public Map<String, String> render(ProjectAnalysis analysis, Orientation orientation) {
        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("system-overview.md", renderOverview(analysis, orientation));
        artifacts.put("endpoints.csv", renderEndpoints(analysis));
        artifacts.put("project-inventory.json", renderJson(analysis));
        analysis.flows().stream()
                .sorted(Comparator.comparing(FlowAnalysis::file).thenComparing(FlowAnalysis::id))
                .forEach(flow -> artifacts.put(flowFileName(flow), renderFlow(flow, analysis, orientation)));
        return Collections.unmodifiableMap(artifacts);
    }

    public static String flowFileName(FlowAnalysis flow) {
        String base = flow.name() == null || flow.name().isBlank() ? flow.file() : flow.name();
        String slug = base.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            slug = "flow";
        }
        if (slug.length() > 64) {
            slug = slug.substring(0, 64);
        }
        return "flow-" + slug + "-" + shortHash(flow.id() + "\n" + flow.file()) + ".md";
    }

    private String renderOverview(ProjectAnalysis analysis, Orientation orientation) {
        long flowCount = analysis.flows().stream().filter(flow -> !flow.subflow()).count();
        long definitionCount = analysis.flows().size() - flowCount;
        StringBuilder out = new StringBuilder();
        out.append("# Структурный инвентарь проекта\n\n")
                .append("Версия схемы: **").append(analysis.schemaVersion()).append("**\n\n")
                .append("Корневые потоки: **").append(flowCount).append("**; определения под-потоков: **")
                .append(definitionCount).append("**; использования конечных точек: **").append(analysis.endpoints().size())
                .append("**. Это статический структурный инвентарь. Достижимость означает структурную возможность и не доказывает выполнение во время работы. ESQL не разбирается. Определение подпотока является шаблоном, а не самостоятельным запускаемым потоком.\n\n")
                .append("## Карточки потоков\n\n");
        for (FlowAnalysis flow : analysis.flows()) {
            out.append("- [").append(md(flow.name().isBlank() ? flow.file() : flow.name()))
                    .append("](").append(flowFileName(flow)).append(") — ")
                    .append(flow.subflow() ? "определение подпотока" : "корневой поток").append('\n');
        }
        out.append('\n')
                .append("## Потоки и конечные точки\n\n")
                .append("```mermaid\n").append(renderGlobalGraph(analysis, orientation)).append("```\n\n")
                .append("## Использования конечных точек\n\n")
                .append("| Поток | Роль | Транспорт | Назначение | Брокер | Происхождение назначения / брокера | Структурная достижимость | Источник |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (EndpointUsage usage : orderedEndpoints(analysis)) {
            FlowAnalysis flow = flowById(analysis, usage.flowId());
            EndpointDescriptor endpoint = usage.endpoint();
            out.append("| ").append(md(flow == null ? usage.flowId() : flow.name())).append(" | ")
                    .append(md(endpoint == null ? "UNKNOWN" : roleLabel(endpoint.role())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : value(endpoint.transport())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : property(endpoint.destination())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : property(endpoint.broker())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : origin(endpoint.destination()) + " / " + origin(endpoint.broker())))
                    .append(" | ").append(md(reachabilityLabel(usage.reachability())))
                    .append(" | ").append(md(source(usage.source()))).append(" |\n");
        }
        out.append("\n## Связи между потоками\n\n| Источник | Получатель | Уверенность связи | Основание |\n|---|---|---|---|\n");
        for (FlowLink link : orderedLinks(analysis)) {
            out.append("| ").append(md(flowLabel(analysis, link.producerFlowId()))).append(" / ")
                    .append(md(endpointLabel(analysis, link.producerEndpointId()))).append(" | ")
                    .append(md(flowLabel(analysis, link.consumerFlowId()))).append(" / ")
                    .append(md(endpointLabel(analysis, link.consumerEndpointId()))).append(" | ")
                    .append(md(linkConfidenceLabel(link.confidence()))).append(" | ").append(md(link.reason())).append(" |\n");
        }
        appendDiagnostics(out, analysis.diagnostics());
        out.append("\n## Пропущенные файлы ESQL\n\n");
        if (analysis.skippedEsqlFiles().isEmpty()) {
            out.append("Не зарегистрированы.\n");
        } else {
            analysis.skippedEsqlFiles().stream().sorted().forEach(file -> out.append("- ").append(md(file)).append('\n'));
        }
        return out.toString();
    }

    private static String renderGlobalGraph(ProjectAnalysis analysis, Orientation orientation) {
        List<FlowAnalysis> flows = analysis.flows().stream()
                .sorted(Comparator.comparing(FlowAnalysis::file).thenComparing(FlowAnalysis::id)).toList();
        List<EndpointUsage> endpoints = orderedEndpoints(analysis);
        int total = flows.size() + endpoints.size();
        int maxNodes = Math.min(total, MAX_OVERVIEW_NODES);
        Map<String, String> ids = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder("flowchart ").append(orientation.name()).append('\n');
        int counter = 0;
        for (FlowAnalysis flow : flows) {
            if (counter >= maxNodes) break;
            ids.put("flow:" + flow.id(), "F" + ++counter);
            out.append("    F").append(counter).append("[\"")
                    .append(mermaid(value(flow.name()) + (flow.subflow() ? " (шаблон под-потока; не запускается отдельно)" : " (корневой поток)")))
                    .append("\"]\n");
        }
        for (EndpointUsage usage : endpoints) {
            if (counter >= maxNodes) break;
            String id = "E" + ++counter;
            ids.put("endpoint:" + usage.id(), id);
            out.append("    ").append(id).append("[\"")
                    .append(mermaid(endpointGraphLabel(usage))).append("\"]\n");
        }
        int edgeCount = 0;
        for (EndpointUsage usage : endpoints) {
            if (edgeCount >= MAX_OVERVIEW_EDGES) break;
            FlowAnalysis flow = flows.stream().filter(item -> java.util.Objects.equals(item.id(), usage.flowId())).findFirst().orElse(null);
            String from = flow != null && !flow.subflow() ? ids.get("flow:" + usage.flowId()) : null;
            String to = ids.get("endpoint:" + usage.id());
            if (from != null && to != null) {
                boolean reads = usage.endpoint() != null && (usage.endpoint().role() == Role.CONSUME
                        || usage.endpoint().role() == Role.FETCH);
                out.append("    ").append(reads ? to : from).append(" --> ")
                        .append(reads ? from : to).append('\n');
                edgeCount++;
            }
        }
        for (FlowLink link : orderedLinks(analysis)) {
            if (edgeCount >= MAX_OVERVIEW_EDGES) break;
            if (!isTopLevelEndpoint(analysis, link.producerEndpointId())
                    || !isTopLevelEndpoint(analysis, link.consumerEndpointId())) continue;
            String from = ids.get("endpoint:" + link.producerEndpointId());
            String to = ids.get("endpoint:" + link.consumerEndpointId());
            if (from != null && to != null) {
                out.append("    ").append(from).append(" -. \"")
                        .append(mermaid(linkConfidenceLabel(link.confidence()))).append("\" .-> ").append(to).append('\n');
                edgeCount++;
            }
        }
        if (total > maxNodes || analysis.endpoints().size() + analysis.links().size() > MAX_OVERVIEW_EDGES) {
            out.append("    LIMITED[\"Обзор сокращён; полный каталог приведён в таблицах и JSON\"]\n");
        }
        if (counter == 0) {
            out.append("    EMPTY[\"Нет разобранных потоков\"]\n");
        }
        return out.toString();
    }

    private String renderFlow(FlowAnalysis flow, ProjectAnalysis analysis, Orientation orientation) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(md(flow.name())).append("\n\n")
                .append("Источник: ").append(md(flow.file())).append("\n\n")
                .append("Тип: **").append(flow.subflow() ? "Определение под-потока (шаблон)" : "Корневой поток").append("**. Показана только статическая структура; выполнение во время работы не подразумевается. ESQL не разбирается; ссылки на обработчики ниже приведены как непрозрачные значения.\n\n")
                .append("## Граф\n\n```mermaid\n").append(renderFlowGraph(flow, orientation)).append("```\n\n")
                .append("## Узлы\n\n| Узел | Тип | Путь экземпляра | Источник |\n|---|---|---|---|\n");
        for (NodeInstance node : flow.nodes()) {
            out.append("| ").append(md(node.label())).append(" | ").append(md(node.kind())).append(" | ")
                    .append(md(node.instancePath())).append(" | ").append(md(source(node.source()))).append(" |\n");
        }
        out.append("\n## Использования конечных точек\n\n| Роль | Транспорт | Назначение | Брокер | Происхождение назначения / брокера | Структурная достижимость | Источник |\n|---|---|---|---|---|---|---|\n");
        for (EndpointUsage usage : orderedEndpoints(analysis).stream().filter(item -> item.flowId().equals(flow.id())).toList()) {
            EndpointDescriptor endpoint = usage.endpoint();
            out.append("| ").append(md(endpoint == null ? "UNKNOWN" : roleLabel(endpoint.role())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : value(endpoint.transport())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : property(endpoint.destination())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : property(endpoint.broker())))
                    .append(" | ").append(md(endpoint == null ? "UNKNOWN" : origin(endpoint.destination()) + " / " + origin(endpoint.broker())))
                    .append(" | ").append(md(reachabilityLabel(usage.reachability())))
                    .append(" | ").append(md(source(usage.source()))).append(" |\n");
        }
        appendSubflows(out, flow.subflows());
        appendProperties(out, flow.nodes());
        appendHandlers(out, flow.handlers());
        appendDiagnostics(out, flow.diagnostics());
        return out.toString();
    }

    private static String renderFlowGraph(FlowAnalysis flow, Orientation orientation) {
        StringBuilder out = new StringBuilder("flowchart ").append(orientation.name()).append('\n');
        Map<String, String> ids = new LinkedHashMap<>();
        int counter = 0;
        for (NodeInstance node : flow.nodes()) {
            String id = "N" + ++counter;
            ids.put(node.id(), id);
            String label = node.label() == null || node.label().isBlank() ? node.nodeId() : node.label();
            out.append("    ").append(id).append("[\"").append(mermaid(label));
            if (node.kind() != null && !node.kind().isBlank()) {
                out.append("<br/>").append(mermaid(node.kind()));
            }
            out.append("\"]\n");
        }
        for (GraphEdge edge : flow.edges()) {
            String from = ids.get(edge.sourceId());
            String to = ids.get(edge.targetId());
            if (from != null && to != null) {
                boolean error = edge.sourceTerminal().equalsIgnoreCase("catch")
                        || edge.sourceTerminal().equalsIgnoreCase("failure");
                String terminals = mermaid(edge.sourceTerminal() + " → " + edge.targetTerminal());
                out.append("    ").append(from).append(error ? " -. \"" : " -->|\"")
                        .append(terminals).append(error ? "\" .-> " : "\"| ").append(to).append('\n');
            }
        }
        if (flow.nodes().isEmpty()) {
            out.append("    EMPTY[\"В определении нет узлов\"]\n");
        }
        return out.toString();
    }

    private static String renderEndpoints(ProjectAnalysis analysis) {
        StringBuilder out = new StringBuilder("идентификатор_потока,имя_потока,файл_потока,идентификатор_узла,роль,транспорт,тип_назначения,назначение,происхождение_назначения,брокер,происхождение_брокера,режим,достижимость,источник,примечание_конечной_точки\r\n");
        for (EndpointUsage usage : orderedEndpoints(analysis)) {
            FlowAnalysis flow = flowById(analysis, usage.flowId());
            EndpointDescriptor endpoint = usage.endpoint();
            List<String> row = List.of(
                    value(usage.flowId()), flow == null ? "UNKNOWN" : value(flow.name()), flow == null ? "UNKNOWN" : value(flow.file()),
                    value(usage.nodeInstanceId()), endpoint == null ? "UNKNOWN" : csvRoleLabel(endpoint.role()),
                    endpoint == null ? "UNKNOWN" : value(endpoint.transport()),
                    endpoint == null ? "UNKNOWN" : value(endpoint.destinationKind()),
                    endpoint == null ? "UNKNOWN" : property(endpoint.destination()),
                    endpoint == null ? "UNKNOWN" : origin(endpoint.destination()),
                    endpoint == null ? "UNKNOWN" : property(endpoint.broker()),
                    endpoint == null ? "UNKNOWN" : origin(endpoint.broker()),
                    endpoint == null ? "UNKNOWN" : value(endpoint.mode()), value(usage.reachability()), source(usage.source()),
                    endpoint == null ? "UNKNOWN" : value(endpoint.note()));
            out.append(row.stream().map(InventoryRenderer::csv).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return out.toString();
    }

    private String renderJson(ProjectAnalysis analysis) {
        try {
            Map<?, ?> recordTree = jsonMapper.convertValue(analysis, Map.class);
            return jsonMapper.writeValueAsString(stableJsonValue(recordTree)) + "\n";
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Could not serialize project inventory", exception);
        }
    }

    private static Object stableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                sorted.put(name, sensitiveKey(name) ? redactJsonValue(item) : stableJsonValue(item));
            });
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(InventoryRenderer::stableJsonValue).toList();
        return value.toString();
    }

    private static Object redactJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new TreeMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                redacted.put(name, "value".equals(name) ? "[REDACTED]" : stableJsonValue(item));
            });
            if (!redacted.containsKey("value")) return "[REDACTED]";
            return redacted;
        }
        return "[REDACTED]";
    }

    private static void appendSubflows(StringBuilder out, List<SubflowInstance> subflows) {
        out.append("\n## Экземпляры под-потоков\n\n| Экземпляр | Узел | Файл определения | Источник |\n|---|---|---|---|\n");
        for (SubflowInstance subflow : subflows) {
            out.append("| ").append(md(subflow.id())).append(" | ").append(md(subflow.nodeId())).append(" | ")
                    .append(md(subflow.definitionFile())).append(" | ").append(md(source(subflow.source()))).append(" |\n");
        }
        for (SubflowInstance subflow : subflows) {
            if (!subflow.parameters().isEmpty()) {
                out.append("\nПараметры экземпляра `").append(md(subflow.id())).append("`:\n\n");
                appendPropertyRows(out, subflow.parameters());
            }
        }
    }

    private static void appendProperties(StringBuilder out, List<NodeInstance> nodes) {
        out.append("\n## Свойства узлов\n\n| Узел | Свойство | Значение | Происхождение | Источники |\n|---|---|---|---|---|\n");
        for (NodeInstance node : nodes) {
            new TreeMap<>(node.properties()).forEach((key, property) -> out.append("| ").append(md(node.label()))
                    .append(" | ").append(md(key)).append(" | ").append(md(safeProperty(key, property)))
                    .append(" | ").append(md(origin(property))).append(" | ").append(md(sources(property.sources())))
                    .append(" |\n"));
        }
    }

    private static void appendPropertyRows(StringBuilder out, Map<String, PropertyValue> properties) {
        out.append("| Свойство | Значение | Происхождение | Источники |\n|---|---|---|---|\n");
        new TreeMap<>(properties).forEach((key, property) -> out.append("| ").append(md(key)).append(" | ")
                .append(md(safeProperty(key, property))).append(" | ").append(md(origin(property))).append(" | ")
                .append(md(sources(property.sources()))).append(" |\n"));
    }

    private static void appendHandlers(StringBuilder out, List<HandlerReference> handlers) {
        out.append("\n## Непрозрачные ссылки на обработчики\n\n| Экземпляр узла | Ссылка на выражение | Источник данных | Источник |\n|---|---|---|---|\n");
        for (HandlerReference handler : handlers) {
            out.append("| ").append(md(handler.nodeInstanceId())).append(" | ").append(md(handler.expression()))
                    .append(" | ").append(md(handler.dataSource())).append(" | ").append(md(source(handler.source()))).append(" |\n");
        }
    }

    private static void appendDiagnostics(StringBuilder out, List<Diagnostic> diagnostics) {
        out.append("\n## Диагностика\n\n| Важность | Код | Поток | Экземпляр узла | Сообщение | Источник |\n|---|---|---|---|---|---|\n");
        diagnostics.stream().sorted(Comparator.comparing((Diagnostic d) -> value(d.severity()))
                        .thenComparing(d -> value(d.code())).thenComparing(d -> value(d.flowId())))
                .forEach(diagnostic -> out.append("| ").append(md(severityLabel(diagnostic.severity()))).append(" | ")
                        .append(md(diagnostic.code())).append(" | ").append(md(diagnostic.flowId())).append(" | ")
                        .append(md(diagnostic.nodeInstanceId())).append(" | ").append(md(diagnostic.message()))
                        .append(" | ").append(md(source(diagnostic.source()))).append(" |\n"));
    }

    private static List<EndpointUsage> orderedEndpoints(ProjectAnalysis analysis) {
        return analysis.endpoints().stream().sorted(Comparator.comparing((EndpointUsage e) -> value(e.flowId()))
                .thenComparing(e -> value(e.nodeInstanceId())).thenComparing(e -> value(e.id()))).toList();
    }

    private static List<FlowLink> orderedLinks(ProjectAnalysis analysis) {
        return analysis.links().stream().sorted(Comparator.comparing((FlowLink l) -> value(l.producerFlowId()))
                .thenComparing(l -> value(l.producerEndpointId())).thenComparing(l -> value(l.consumerFlowId()))
                .thenComparing(l -> value(l.consumerEndpointId()))).toList();
    }

    private static FlowAnalysis flowById(ProjectAnalysis analysis, String id) {
        return analysis.flows().stream().filter(flow -> java.util.Objects.equals(flow.id(), id)).findFirst().orElse(null);
    }

    private static boolean isTopLevelEndpoint(ProjectAnalysis analysis, String endpointId) {
        EndpointUsage usage = analysis.endpoints().stream()
                .filter(endpoint -> java.util.Objects.equals(endpoint.id(), endpointId)).findFirst().orElse(null);
        FlowAnalysis flow = usage == null ? null : flowById(analysis, usage.flowId());
        return flow != null && !flow.subflow();
    }

    private static String flowLabel(ProjectAnalysis analysis, String id) {
        FlowAnalysis flow = flowById(analysis, id);
        if (flow == null) return value(id);
        return value(flow.name()) + (flow.subflow() ? " (шаблон определения; не запускается отдельно)" : "");
    }

    private static String endpointLabel(ProjectAnalysis analysis, String id) {
        return analysis.endpoints().stream().filter(endpoint -> java.util.Objects.equals(endpoint.id(), id))
                .map(InventoryRenderer::endpointGraphLabel).findFirst().orElse(value(id));
    }

    private static String endpointGraphLabel(EndpointUsage usage) {
        EndpointDescriptor endpoint = usage.endpoint();
        if (endpoint == null) return usage.id() + " / UNKNOWN";
        return endpoint.transport() + " / " + property(endpoint.destination()) + " @ "
                + property(endpoint.broker()) + " / " + roleLabel(endpoint.role());
    }

    private static String roleLabel(Role role) {
        if (role == Role.FETCH) return "FETCH (выборка MQGet)";
        if (role == Role.CONSUME) return "CONSUME (вход корневого потока)";
        return switch (role == null ? "" : role.name()) {
            case "PRODUCE" -> "PRODUCE (публикация/отправка)";
            case "REPLY" -> "REPLY (ответ)";
            default -> "UNKNOWN";
        };
    }

    private static String csvRoleLabel(Role role) {
        return role == null ? "UNKNOWN" : roleLabel(role);
    }

    private static String reachabilityLabel(Reachability reachability) {
        if (reachability == null) return "UNKNOWN";
        return switch (reachability) {
            case STRUCTURAL_NORMAL -> "Обычный путь структурно возможен";
            case STRUCTURAL_ERROR -> "Возможен по ветви ошибки";
            case STRUCTURAL_MIXED -> "Возможны обычный и ошибочный пути";
            case UNCONNECTED -> "Не подключена в структуре";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private static String linkConfidenceLabel(LinkConfidence confidence) {
        if (confidence == null) return "UNKNOWN";
        return switch (confidence) {
            case CONFIGURED -> "Настроенная связь (CONFIGURED)";
            case CANDIDATE -> "Кандидат по контексту брокера (CANDIDATE)";
        };
    }

    private static String severityLabel(Severity severity) {
        if (severity == null) return "UNKNOWN";
        return switch (severity) {
            case INFO -> "Информация";
            case WARNING -> "Предупреждение";
            case ERROR -> "Ошибка";
        };
    }

    private static String property(PropertyValue property) {
        if (property == null || property.origin() == PropertyValue.Origin.UNKNOWN || property.value() == null || property.value().isBlank()) return "UNKNOWN";
        return property.value();
    }

    private static String safeProperty(String name, PropertyValue property) {
        return sensitiveKey(name) ? "[REDACTED]" : property(property);
    }

    private static boolean sensitiveKey(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        return key.contains("password") || key.contains("secret") || key.contains("token") || key.contains("authorization");
    }

    private static String origin(PropertyValue property) {
        return property == null || property.origin() == null ? "UNKNOWN" : property.origin().name();
    }

    private static String source(SourceRef source) {
        if (source == null) return "UNKNOWN";
        return value(source.file()) + (source.line() > 0 ? ":" + source.line() : "") + (source.column() > 0 ? ":" + source.column() : "");
    }

    private static String sources(List<SourceRef> sources) {
        return sources == null || sources.isEmpty() ? "UNKNOWN" : sources.stream().map(InventoryRenderer::source).sorted().collect(java.util.stream.Collectors.joining("; "));
    }

    private static String value(Object value) {
        return value == null ? "UNKNOWN" : value.toString();
    }

    private static String md(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("|", "&#124;").replace("`", "&#96;").replace("[", "&#91;").replace("]", "&#93;")
                .replace("!", "&#33;").replace("*", "&#42;").replace("_", "&#95;").replace("\\", "&#92;")
                .replace("\r", " ").replace("\n", " ").replace("\t", " ");
    }

    private static String mermaid(String value) {
        return MermaidEscaper.escape(value).replace("`", "&#96;");
    }

    private static String csv(String raw) {
        String value = raw == null ? "" : raw;
        int first = 0;
        while (first < value.length() && Character.isWhitespace(value.charAt(first))) first++;
        if (first < value.length() && "=+-@".indexOf(value.charAt(first)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 4; index++) hex.append(String.format("%02x", digest[index]));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
