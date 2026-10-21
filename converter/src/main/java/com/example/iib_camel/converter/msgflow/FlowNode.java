package com.example.iib_camel.converter.msgflow;

import java.util.List;
import java.util.Map;

/**
 * Узел потока. kind — каноническое имя типа без префикса ComIbm и суффикса .msgnode
 * (например MQInput, Compute, Route); для подпотоков — "Subflow".
 */
public record FlowNode(String id, String kind, String label, Map<String, String> properties,
                       List<String> dynamicOutTerminals) {
}
