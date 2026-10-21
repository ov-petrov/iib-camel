package com.example.iib_camel.converter.diagram;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Реестр категорий узлов IIB для стилизации диаграммы
 * (docs/diagram-renderer-design.ru.md, раздел 4.2, стартовый список).
 *
 * Ключ — kind из IR: каноническое имя типа узла без префикса ComIbm и суффикса .msgnode
 * (например MQInput, Compute, Subflow), сравнение без учёта регистра.
 * Неизвестный тип отображается категорией OTHER, ссылки на подпотоки — REFERENCE;
 * реестр пополняется по мере появления реальных файлов (допущение A8).
 */
public final class NodeCategoryRegistry {

    private static final String SUBFLOW_KIND = "subflow";

    private final Map<String, NodeCategory> byKind = new HashMap<>();

    /** Дефолтный реестр со стартовым набором типов узлов IIB (таблица раздела 4.2 дизайн-документа). */
    public static NodeCategoryRegistry withIibDefaults() {
        NodeCategoryRegistry registry = new NodeCategoryRegistry();
        for (String kind : List.of("MQInput", "HTTPInput", "JMSInput", "FileInput",
                                   "SOAPInput", "TCPInput", "MQGet")) {
            registry.register(kind, NodeCategory.INPUT);
        }
        for (String kind : List.of("MQOutput", "HTTPReply", "JMSOutput", "FileOutput",
                                   "SOAPReply", "TCPOutput", "MQReply")) {
            registry.register(kind, NodeCategory.OUTPUT);
        }
        for (String kind : List.of("Filter", "Route", "RouteToLabel", "Label", "Router")) {
            registry.register(kind, NodeCategory.ROUTE);
        }
        for (String kind : List.of("Compute", "JavaCompute", "Mapping", ".NETCompute", "Database",
                                   "Collector", "Aggregate", "Resequence", "Split",
                                   "TimeoutControl", "TimeoutNotify", "SetVariable",
                                   "ResetContentDescriptor", "PASSTHRU", "Trace")) {
            registry.register(kind, NodeCategory.PROCESS);
        }
        for (String kind : List.of("Throw", "TryCatch")) {
            registry.register(kind, NodeCategory.ERROR);
        }
        return registry;
    }

    public void register(String kind, NodeCategory category) {
        byKind.put(normalize(kind), Objects.requireNonNull(category, "category"));
    }

    /** Категоризация: подпотоки — REFERENCE, известные типы — по реестру, неизвестные — OTHER. */
    public NodeCategory categorize(String kind) {
        if (SUBFLOW_KIND.equals(normalize(kind))) {
            return NodeCategory.REFERENCE;
        }
        NodeCategory category = byKind.get(normalize(kind));
        return category != null ? category : NodeCategory.OTHER;
    }

    private static String normalize(String kind) {
        return kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim();
    }
}
