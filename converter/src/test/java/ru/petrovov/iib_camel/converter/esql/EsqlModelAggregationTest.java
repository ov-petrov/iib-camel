package ru.petrovov.iib_camel.converter.esql;

import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.converter.report.UnitStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EsqlModelAggregationTest {

    private final EsqlToJavaGenerator generator = new EsqlToJavaGenerator();

    @Test
    void aggregatesCompatibleFieldsAcrossModuleStatements() {
        var models = generator.generateModels(List.of(
                statement("SET OutputRoot.XMLNSC.Order.Status = 'OPEN';", "Order.Status"),
                statement("SET OutputRoot.XMLNSC.Order.Rating = '100';", "Order.Rating")));

        assertEquals(1, models.size());
        assertEquals("Order", models.getFirst().className());
        assertTrue(models.getFirst().java().contains("private String status;"));
        assertTrue(models.getFirst().java().contains("private String rating;"));
    }

    @Test
    void mergesRepeatedFullPathsWithoutSourceComments() {
        var models = generator.generateModels(List.of(
                statement("SET OutputRoot.XMLNSC.Order.Status = 'OPEN';", "Order.Status"),
                statement("SET OutputRoot.XMLNSC.Order.Status = 'CLOSED';", "Order.Status")));

        assertEquals(1, models.size());
        String java = models.getFirst().java();
        assertEquals(1, occurrences(java, "private String status;"));
        assertFalse(java.contains("//"));
        assertFalse(java.contains("/*"));
        assertFalse(java.contains("SET OutputRoot"));
    }

    @Test
    void diagnosesDistinctRootsThatNormalizeToTheSameClass() {
        var error = assertThrows(IllegalArgumentException.class, () -> generator.generateModels(List.of(
                statement("SET first", "Order-Data.Status"),
                statement("SET second", "Order_Data.Rating"))));
        assertTrue(error.getMessage().contains("OrderData"));
    }

    @Test
    void diagnosesDifferentPathsThatFlattenToTheSameField() {
        var error = assertThrows(IllegalArgumentException.class, () -> generator.generateModels(List.of(
                statement("SET first", "Order.Parent.Customer.Status"),
                statement("SET second", "Order.Parent.Account.Status"))));
        assertTrue(error.getMessage().contains("сворачиваются в поле Order.status"));
    }

    private EsqlTranslator.StatementTranslation statement(String source, String path) {
        return new EsqlTranslator.StatementTranslation(source,
                "setBodyField(exchange, \"" + path + "\", \"value\");",
                UnitStatus.CONVERTED, "");
    }

    private int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }
}
