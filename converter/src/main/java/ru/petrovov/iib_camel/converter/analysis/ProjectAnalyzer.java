package ru.petrovov.iib_camel.converter.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.petrovov.iib_camel.converter.analysis.endpoint.EndpointExtractor;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor;
import ru.petrovov.iib_camel.converter.analysis.model.EndpointDescriptor.Role;
import ru.petrovov.iib_camel.converter.analysis.model.FlowDocument;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Diagnostic;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.EndpointUsage;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.FlowAnalysis;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.FlowLink;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.LinkConfidence;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Reachability;
import ru.petrovov.iib_camel.converter.analysis.model.ProjectAnalysis.Severity;
import ru.petrovov.iib_camel.converter.analysis.model.PropertyValue;
import ru.petrovov.iib_camel.converter.analysis.resolve.FlowExpander;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;

public final class ProjectAnalyzer {
    private static final int SCHEMA_VERSION = 1;
    private final EndpointExtractor extractor = new EndpointExtractor();

    public ProjectAnalysis analyze(List<FlowDocument> documents) {
        return analyze(documents, List.of(), List.of());
    }

    public ProjectAnalysis analyze(List<FlowDocument> documents, List<Diagnostic> sourceDiagnostics,
                                     List<String> skippedEsqlFiles) {
        List<FlowDocument> ordered = documents.stream().sorted(Comparator.comparing(FlowDocument::file)).toList();
        FlowExpander expander = new FlowExpander(ordered);
        List<FlowAnalysis> flows = new ArrayList<>();
        List<EndpointUsage> endpoints = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>(sourceDiagnostics);

        for (FlowDocument document : ordered) {
            var expansion = expander.expand(document);
            List<Diagnostic> flowIssues = new ArrayList<>(expansion.diagnostics());
            List<EndpointUsage> usages = endpoints(document, expansion, flowIssues);
            endpoints.addAll(usages);
            diagnostics.addAll(flowIssues);
            flows.add(new FlowAnalysis(document.file(), document.file(), document.flow().name(), document.subflow(),
                    expansion.nodes(), expansion.edges(), expansion.subflows(), expansion.handlers(),
                    usages.stream().map(EndpointUsage::id).toList(), flowIssues));
        }
        List<FlowLink> links = links(flows, endpoints);
        return new ProjectAnalysis(SCHEMA_VERSION, flows, endpoints, links, diagnostics,
                skippedEsqlFiles.stream().sorted().toList());
    }

    private List<EndpointUsage> endpoints(FlowDocument document, FlowExpander.Expansion expansion,
                                           List<Diagnostic> diagnostics) {
        List<EndpointUsage> result = new ArrayList<>();
        Set<String> roots = new HashSet<>();
        if (document.subflow()) {
            expansion.nodes().stream().filter(node -> node.kind().equals("Input") && node.instancePath().isEmpty())
                    .forEach(node -> roots.add(node.id()));
        }
        for (var node : expansion.nodes()) {
            var raw = new FlowNode(node.nodeId(), node.kind(), node.label(), Map.of(), List.of());
            extractor.extract(raw, node.properties(), node.source()).ifPresent(endpoint -> {
                if (endpoint.role() == Role.CONSUME) {
                    roots.add(node.id());
                }
                result.add(new EndpointUsage(node.id() + "::endpoint", document.file(), node.id(), endpoint,
                        Reachability.UNKNOWN, node.source()));
                if (!known(endpoint.destination())) {
                    diagnostics.add(new Diagnostic(Code.UNKNOWN_DESTINATION.name(), Severity.WARNING,
                            document.file(), node.id(), "Назначение не определено: " + endpoint.note(), node.source()));
                }
                if (!known(endpoint.broker())) {
                    diagnostics.add(new Diagnostic(Code.UNKNOWN_BROKER_CONTEXT.name(), Severity.WARNING,
                            document.file(), node.id(), "Контекст брокера не определён по исходникам", node.source()));
                }
            });
        }
        Map<String, Reachability> potentialPaths = StructuralReachability.classify(roots, expansion.edges());
        Set<String> barriers = new HashSet<>();
        Set<String> uncertainSeeds = new HashSet<>();
        for (var node : expansion.nodes()) {
            if (node.kind().equals("Subflow") || node.kind().equals("UnresolvedTerminal")) {
                barriers.add(node.id());
                if (potentialPaths.containsKey(node.id())) {
                    uncertainSeeds.add(node.id());
                }
            }
            if (node.kind().equals("UnresolvedTerminal")) {
                List<String> scope = expansion.nodes().stream()
                        .filter(member -> member.id().startsWith(node.instancePath() + "/"))
                        .map(member -> member.id()).toList();
                if (potentialPaths.containsKey(node.id()) || scope.stream().anyMatch(potentialPaths::containsKey)) {
                    uncertainSeeds.add(node.id());
                    uncertainSeeds.addAll(scope);
                }
            }
        }
        Map<String, Reachability> paths = StructuralReachability.classify(roots, expansion.edges(), barriers);
        Set<String> uncertain = StructuralReachability.classify(uncertainSeeds, expansion.edges()).keySet();
        return result.stream().map(usage -> new EndpointUsage(usage.id(), usage.flowId(), usage.nodeInstanceId(),
                usage.endpoint(), paths.getOrDefault(usage.nodeInstanceId(), roots.isEmpty() || uncertain.contains(usage.nodeInstanceId())
                        ? Reachability.UNKNOWN : Reachability.UNCONNECTED), usage.source())).toList();
    }

    private static List<FlowLink> links(List<FlowAnalysis> flows, List<EndpointUsage> endpoints) {
        Set<String> definitions = new HashSet<>();
        flows.stream().filter(FlowAnalysis::subflow).forEach(flow -> definitions.add(flow.id()));
        List<EndpointUsage> concrete = endpoints.stream().filter(endpoint -> !definitions.contains(endpoint.flowId()))
                .filter(endpoint -> known(endpoint.endpoint().destination())).toList();
        List<FlowLink> result = new ArrayList<>();
        for (EndpointUsage producer : concrete) {
            if (producer.endpoint().role() != Role.PRODUCE && producer.endpoint().role() != Role.REPLY) {
                continue;
            }
            for (EndpointUsage consumer : concrete) {
                if (consumer.endpoint().role() != Role.CONSUME && consumer.endpoint().role() != Role.FETCH) {
                    continue;
                }
                EndpointDescriptor from = producer.endpoint();
                EndpointDescriptor to = consumer.endpoint();
                if (from.transport() != to.transport() || from.destinationKind() != to.destinationKind()
                        || !from.destination().value().equals(to.destination().value())) {
                    continue;
                }
                boolean brokerKnown = known(from.broker()) && known(to.broker());
                if (brokerKnown && !from.broker().value().equals(to.broker().value())) {
                    continue;
                }
                boolean connected = producer.reachability() != Reachability.UNCONNECTED
                        && producer.reachability() != Reachability.UNKNOWN
                        && consumer.reachability() != Reachability.UNCONNECTED
                        && consumer.reachability() != Reachability.UNKNOWN;
                LinkConfidence confidence = brokerKnown && connected ? LinkConfidence.CONFIGURED : LinkConfidence.CANDIDATE;
                String reason = !brokerKnown ? "Совпало имя назначения; контекст брокера требует подтверждения"
                        : !connected ? "Совпала конфигурация; структурная связь со входом не установлена"
                        : "Совпали транспорт, назначение и заданный контекст брокера; выполнение не подтверждено";
                result.add(new FlowLink(producer.flowId(), producer.id(), consumer.flowId(), consumer.id(),
                        confidence, reason));
            }
        }
        return result;
    }

    private static boolean known(PropertyValue value) {
        return value.origin() != PropertyValue.Origin.UNKNOWN && !value.value().isBlank();
    }

    private enum Code {
        UNKNOWN_DESTINATION,
        UNKNOWN_BROKER_CONTEXT
    }
}
