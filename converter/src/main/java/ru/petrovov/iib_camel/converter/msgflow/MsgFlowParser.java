package ru.petrovov.iib_camel.converter.msgflow;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MsgFlowParser {

    private static final String ECORE_NS = "http://www.eclipse.org/emf/2002/Ecore";
    private static final String XMI_NS = "http://www.omg.org/XMI";
    private static final String EFLOW_NS = "http://www.ibm.com/wbi/2005/eflow";
    private static final Set<String> SKIPPED_ATTRS = Set.of("location");

    public MsgFlow parse(InputStream input) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = dbf.newDocumentBuilder().parse(input);
            return fromDocument(doc);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("ошибка разбора .msgflow: " + e.getMessage(), e);
        }
    }

    private MsgFlow fromDocument(Document doc) {
        Element root = doc.getDocumentElement();
        if (root == null || !"EPackage".equals(root.getLocalName())
                || !ECORE_NS.equals(root.getNamespaceURI())) {
            throw new IllegalArgumentException("файл не является .msgflow (корневой элемент: "
                    + (root == null ? "null" : root.getNodeName()) + ")");
        }
        String name = root.getAttribute("nsURI");

        List<FlowNode> nodes = new ArrayList<>();
        var nodeElements = doc.getElementsByTagNameNS("*", "nodes");
        for (int i = 0; i < nodeElements.getLength(); i++) {
            nodes.add(node((Element) nodeElements.item(i)));
        }

        List<FlowConnection> connections = new ArrayList<>();
        var connectionElements = doc.getElementsByTagNameNS("*", "connections");
        Set<String> ids = new java.util.HashSet<>();
        for (FlowNode node : nodes) {
            ids.add(node.id());
        }
        for (int i = 0; i < connectionElements.getLength(); i++) {
            Element element = (Element) connectionElements.item(i);
            String id = xmiAttribute(element, "id");
            if (!id.isBlank() && !ids.add(id)) {
                throw new IllegalArgumentException("повторяющийся xmi:id связи: " + id);
            }
            connections.add(connection(element));
        }
        validateGraph(nodes, connections);
        return new MsgFlow(name, nodes, connections);
    }

    private FlowNode node(Element el) {
        String xmiType = xmiAttribute(el, "type");
        if (xmiType.isBlank()) {
            throw new IllegalArgumentException("узел без xmi:type (id=" + xmiAttribute(el, "id") + ")");
        }
        String id = requiredXmiAttr(el, "id");
        String kind = nodeKind(el, xmiType);
        Map<String, String> properties = new LinkedHashMap<>();
        var attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            attr(properties, attrs.item(i));
        }
        if ("Subflow".equals(kind)) {
            properties.put("subflowName", subflowTarget(el, xmiType));
        }

        List<String> dynamicTerminals = new ArrayList<>();
        List<RouteRule> routeRules = new ArrayList<>();
        String label = null;
        var children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            switch (localName(child)) {
                case "translation" -> label = child.getAttribute("string");
                case "outTerminals" -> {
                    String terminal = child.getAttribute("terminalNodeID");
                    if (terminal.isBlank()) {
                        throw new IllegalArgumentException("пустой terminalNodeID у узла " + id);
                    }
                    dynamicTerminals.add(terminal);
                }
                case "filterTable" -> {
                    String terminal = child.getAttribute("routingOutputTerminal");
                    if (terminal.isBlank()) {
                        throw new IllegalArgumentException("пустой routingOutputTerminal у узла " + id);
                    }
                    routeRules.add(new RouteRule(child.getAttribute("filterPattern"), terminal));
                    var childAttrs = child.getAttributes();
                    for (int a = 0; a < childAttrs.getLength(); a++) {
                        Node attr = childAttrs.item(a);
                        properties.putIfAbsent("filterTable." + attr.getNodeName(), attr.getNodeValue());
                    }
                }
                default -> {
                    var childAttrs = child.getAttributes();
                    for (int a = 0; a < childAttrs.getLength(); a++) {
                        Node attr = childAttrs.item(a);
                        String key = localName(child) + "." + attr.getNodeName();
                        properties.put(key, attr.getNodeValue());
                    }
                }
            }
        }
        if (label == null) {
            label = id;
        }
        return new FlowNode(id, kind, label, properties, dynamicTerminals, routeRules);
    }

    private void attr(Map<String, String> properties, Node attr) {
        String name = attr.getNodeName();
        if (SKIPPED_ATTRS.contains(name)) {
            return;
        }
        if (XMI_NS.equals(attr.getNamespaceURI()) && Set.of("id", "type").contains(localName(attr))) {
            return;
        }
        properties.put(name, attr.getNodeValue());
    }

    private FlowConnection connection(Element el) {
        return new FlowConnection(
                requireAttr(el, "sourceNode"),
                terminal(requireAttr(el, "sourceTerminalName")),
                requireAttr(el, "targetNode"),
                terminal(requireAttr(el, "targetTerminalName")));
    }

    private String terminal(String raw) {
        int dot = raw.indexOf('.');
        if (raw.startsWith("OutTerminal.") || raw.startsWith("InTerminal.")) {
            raw = raw.substring(dot + 1);
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("пустая ссылка на терминал в связи");
        }
        return raw;
    }

    private String requireAttr(Element el, String name) {
        String value = el.getAttribute(name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("отсутствует обязательный атрибут \"" + name
                    + "\" у элемента " + localName(el));
        }
        return value;
    }

    private String requiredXmiAttr(Element el, String local) {
        String value = xmiAttribute(el, local);
        if (value.isBlank()) {
            throw new IllegalArgumentException("отсутствует обязательный атрибут \"xmi:" + local
                    + "\" у элемента " + localName(el));
        }
        return value;
    }

    private String xmiAttribute(Element el, String local) {
        var attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            if (XMI_NS.equals(attr.getNamespaceURI()) && local.equals(localName(attr))) {
                return attr.getNodeValue();
            }
        }
        return "";
    }

    private String nodeKind(Element element, String xmiType) {
        String namespace = typeNamespace(element, xmiType);
        String local = typeLocalName(xmiType);
        if (namespace.endsWith(".subflow")) {
            return "Subflow";
        }
        if (EFLOW_NS.equals(namespace)) {
            return switch (local) {
                case "FCMSource" -> "Input";
                case "FCMSink" -> "Output";
                default -> xmiType;
            };
        }
        if (namespace.endsWith(".msgnode")) {
            String body = namespace.substring(0, namespace.length() - ".msgnode".length());
            return body.startsWith("ComIbm") ? body.substring("ComIbm".length()) : body;
        }
        return namespace.isEmpty() ? local : namespace;
    }

    private String subflowTarget(Element element, String xmiType) {
        String namespace = typeNamespace(element, xmiType);
        return namespace.endsWith(".subflow")
                ? namespace.substring(0, namespace.length() - ".subflow".length())
                : subflowTarget(xmiType);
    }

    private String typeNamespace(Element element, String xmiType) {
        int colon = xmiType.indexOf(':');
        if (colon < 0) {
            return element.lookupNamespaceURI(null) == null ? "" : element.lookupNamespaceURI(null);
        }
        String namespace = element.lookupNamespaceURI(xmiType.substring(0, colon));
        return namespace == null ? "" : namespace;
    }

    private String typeLocalName(String xmiType) {
        int colon = xmiType.indexOf(':');
        return colon < 0 ? xmiType : xmiType.substring(colon + 1);
    }

    private void validateGraph(List<FlowNode> nodes, List<FlowConnection> connections) {
        Set<String> nodeIds = new java.util.HashSet<>();
        for (FlowNode node : nodes) {
            if (node.id().isBlank()) {
                throw new IllegalArgumentException("обнаружен узел с пустым xmi:id");
            }
            if (!nodeIds.add(node.id())) {
                throw new IllegalArgumentException("повторяющийся xmi:id узла: " + node.id());
            }
        }
        for (FlowConnection connection : connections) {
            if (!nodeIds.contains(connection.sourceNodeId())) {
                throw new IllegalArgumentException("связь ссылается на отсутствующий sourceNode: "
                        + connection.sourceNodeId());
            }
            if (!nodeIds.contains(connection.targetNodeId())) {
                throw new IllegalArgumentException("связь ссылается на отсутствующий targetNode: "
                        + connection.targetNodeId());
            }
        }
    }

    private String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    static String nodeKind(String xmiType) {
        int colon = xmiType.indexOf(':');
        String prefix = colon < 0 ? xmiType : xmiType.substring(0, colon);
        if (prefix.endsWith(".subflow")) {
            return "Subflow";
        }
        if (prefix.equals("eflow")) {
            String local = xmiType.substring(colon + 1);
            return switch (local) {
                case "FCMSource" -> "Input";
                case "FCMSink" -> "Output";
                default -> xmiType;
            };
        }
        if (prefix.endsWith(".msgnode")) {
            String body = prefix.substring(0, prefix.length() - ".msgnode".length());
            return body.startsWith("ComIbm") ? body.substring("ComIbm".length()) : body;
        }
        return prefix;
    }

    static String subflowTarget(String xmiType) {
        int colon = xmiType.indexOf(':');
        String prefix = colon < 0 ? xmiType : xmiType.substring(0, colon);
        return prefix.endsWith(".subflow")
                ? prefix.substring(0, prefix.length() - ".subflow".length())
                : prefix;
    }
}
