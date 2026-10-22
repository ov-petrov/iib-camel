package com.example.iib_camel.converter.msgflow;

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

/**
 * Парсер .msgflow (формат Eclipse EMF/XMI, namespace eflow) в промежуточную модель IR.
 * FR-1.1, FR-1.3, FR-1.4 из docs/functional-requirements.ru.md.
 */
public final class MsgFlowParser {

    private static final String ECORE_NS = "http://www.eclipse.org/emf/2002/Ecore";
    /** Шумовые атрибуты фигур (позиции на канве) — в модель не включаются. */
    private static final Set<String> SKIPPED_ATTRS = Set.of("xmi:type", "xmi:id", "location");

    public MsgFlow parse(InputStream input) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
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
        var nodeElements = doc.getElementsByTagName("nodes");
        for (int i = 0; i < nodeElements.getLength(); i++) {
            nodes.add(node((Element) nodeElements.item(i)));
        }

        List<FlowConnection> connections = new ArrayList<>();
        var connectionElements = doc.getElementsByTagName("connections");
        for (int i = 0; i < connectionElements.getLength(); i++) {
            connections.add(connection((Element) connectionElements.item(i)));
        }
        return new MsgFlow(name, nodes, connections);
    }

    private FlowNode node(Element el) {
        String xmiType = el.getAttribute("xmi:type");
        if (xmiType.isBlank()) {
            throw new IllegalArgumentException("узел без xmi:type (id=" + el.getAttribute("xmi:id") + ")");
        }
        String id = requireAttr(el, "xmi:id");
        String kind = nodeKind(xmiType);
        Map<String, String> properties = new LinkedHashMap<>();
        var attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            attr(properties, attrs.item(i));
        }
        // узел-ссылка на подпоток: цель берём из xmi:type (канон ACE: "<Имя>.subflow:FCMComposite_1") —
        // реальные .msgflow не хранят имя файла подпотока в отдельном атрибуте узла (A4)
        if ("Subflow".equals(kind)) {
            properties.put("subflowName", subflowTarget(xmiType));
        }

        List<String> dynamicTerminals = new ArrayList<>();
        String label = null;
        var children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            switch (child.getTagName()) {
                case "translation" -> label = child.getAttribute("string");
                case "outTerminals" -> dynamicTerminals.add(child.getAttribute("terminalNodeID"));
                // вложенные таблицы правил (filterTable у Route и т.п.) поднимаем в свойства узла
                default -> {
                    var childAttrs = child.getAttributes();
                    for (int a = 0; a < childAttrs.getLength(); a++) {
                        Node attr = childAttrs.item(a);
                        String key = child.getTagName() + "." + attr.getNodeName();
                        properties.put(key, attr.getNodeValue());
                    }
                }
            }
        }
        if (label == null) {
            label = el.getAttribute("xmi:id");
        }
        return new FlowNode(id, kind, label, properties, dynamicTerminals);
    }

    private void attr(Map<String, String> properties, Node attr) {
        String name = attr.getNodeName();
        if (SKIPPED_ATTRS.contains(name)) {
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
            return raw.substring(dot + 1);
        }
        return raw;
    }

    private String requireAttr(Element el, String name) {
        String value = el.getAttribute(name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("отсутствует обязательный атрибут \"" + name
                    + "\" у элемента " + el.getTagName());
        }
        return value;
    }

    /**
     * Канон типов узлов — ot4i/ace-bob, skills/shared/node-types.md:
     * ComIbm*.msgnode — built-in узлы; "&lt;Имя&gt;.subflow:FCMComposite_1" — узел-ссылка
     * на подпоток (брокер-схема, если есть, присоединена подчёркиванием: "Схема_Имя.subflow");
     * eflow:FCMSource — Input-узел подпотока (терминал In узла-ссылки);
     * eflow:FCMSink — Output-узел подпотока (терминал Out).
     */
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
                default -> xmiType; // прочие служебные eflow-типы: как есть, диагностика справится
            };
        }
        if (prefix.endsWith(".msgnode")) {
            String body = prefix.substring(0, prefix.length() - ".msgnode".length());
            return body.startsWith("ComIbm") ? body.substring("ComIbm".length()) : body;
        }
        return prefix;
    }

    /**
     * Имя файла подпотока из канонического xmi:type узла-ссылки:
     * "Check.subflow:FCMComposite_1" → "Check"; "Schema_Check.subflow:FCMComposite_1" →
     * "Schema_Check" (брокер-схема сохраняется — резолвер сопоставит по полному имени,
     * см. RouteGenerator: ключи реестра подпотоков включают и полное, и короткое имя).
     */
    static String subflowTarget(String xmiType) {
        int colon = xmiType.indexOf(':');
        String prefix = colon < 0 ? xmiType : xmiType.substring(0, colon);
        return prefix.endsWith(".subflow")
                ? prefix.substring(0, prefix.length() - ".subflow".length())
                : prefix;
    }
}
