package ru.petrovov.iib_camel.converter.diagram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Реестр категорий: kind → категория (дизайн-документ, раздел 4.2).
 */
class NodeCategoryRegistryTest {

    private final NodeCategoryRegistry registry = NodeCategoryRegistry.withIibDefaults();

    @Test
    void knownKindsAreCategorized() {
        assertEquals(NodeCategory.INPUT, registry.categorize("MQInput"));
        assertEquals(NodeCategory.OUTPUT, registry.categorize("MQOutput"));
        assertEquals(NodeCategory.ROUTE, registry.categorize("Route"));
        assertEquals(NodeCategory.PROCESS, registry.categorize("Compute"));
        assertEquals(NodeCategory.ERROR, registry.categorize("Throw"));
    }

    @Test
    void kindComparisonIsCaseInsensitive() {
        assertEquals(NodeCategory.INPUT, registry.categorize("mqinput"));
        assertEquals(NodeCategory.ROUTE, registry.categorize("RoUtE"));
    }

    @Test
    void subflowKindIsReference() {
        assertEquals(NodeCategory.REFERENCE, registry.categorize("Subflow"));
        assertEquals(NodeCategory.REFERENCE, registry.categorize("subflow"));
    }

    @Test
    void unknownKindFallsBackToOther() {
        assertEquals(NodeCategory.OTHER, registry.categorize("ZzzUnkNown"));
    }

    @Test
    void registryIsExtensible() {
        registry.register("Gadget", NodeCategory.PROCESS);
        assertEquals(NodeCategory.PROCESS, registry.categorize("Gadget"));
    }
}
