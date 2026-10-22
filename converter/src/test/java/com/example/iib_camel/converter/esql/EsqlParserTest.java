package com.example.iib_camel.converter.esql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Юнит-тесты парсера ESQL на fixture corr-01-simple-adapter (уровень L1 стратегии тестирования). */
class EsqlParserTest {

    private static String adapterEsql() {
        try (var in = EsqlParserTest.class.getResourceAsStream("/corpus/corr-01-simple-adapter/Adapter_Compute1.esql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void parsesModuleSkeleton() {
        var module = new EsqlParser().parse(adapterEsql());
        assertEquals("adapter", module.schema());
        assertEquals("Adapter_Compute1", module.name());
        assertEquals("COMPUTE", module.type());
    }

    @Test
    void parsesMainFunction() {
        var function = new EsqlParser().parse(adapterEsql())
                .function("Main")
                .orElseThrow();
        assertEquals("BOOLEAN", function.returnType());
        assertTrue(function.returnsTrue());
    }

    @Test
    void capturesSetAssignments() {
        var function = new EsqlParser().parse(adapterEsql())
                .function("Main")
                .orElseThrow();
        assertTrue(function.assignments().stream()
                .anyMatch(a -> a.target().equals("OutputRoot") && a.value().equals("InputRoot")));
        assertTrue(function.assignments().stream()
                .anyMatch(a -> a.target().equals("OutputRoot.MQMD.ApplicationIdData")
                        && a.value().equals("'IIB-CAMEL-MIG'")));
        assertFalse(function.assignments().isEmpty());
    }

    @Test
    void parsesMultipleModules() {
        String twoModules = """
                BROKER SCHEMA test;
                CREATE COMPUTE MODULE One COMPUTE FullMode;
                CREATE FUNCTION Main() RETURNS BOOLEAN
                BEGIN
                  SET OutputRoot = InputRoot;
                  RETURN TRUE;
                END;
                END MODULE;
                CREATE COMPUTE MODULE Two
                CREATE FUNCTION Main() RETURNS BOOLEAN
                BEGIN
                  RETURN FALSE;
                END;
                END MODULE;
                """;
        var modules = new EsqlParser().parseAll(twoModules);
        assertEquals(2, modules.size());
        assertEquals("COMPUTE", modules.getFirst().type());
        assertTrue(new EsqlParser().parseAll(twoModules).get(1).function("Main").orElseThrow()
                .assignments().isEmpty());
    }
}
