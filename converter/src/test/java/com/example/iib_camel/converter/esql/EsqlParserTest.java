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

    @Test
    void detectsUnsupportedFragments() {
        String esql = """
                BROKER SCHEMA test
                CREATE COMPUTE MODULE Branching
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot = InputRoot;
                    SET InputRoot.Variables.flag = '1';
                    IF InputBody.Order.Status = 'HOLD' THEN
                      SET OutputRoot.XMLNSC.Order.Status = 'PAUSED';
                    END IF;
                    WHILE InputRoot.Variables.i < 10 DO
                      SET InputRoot.Variables.i = InputRoot.Variables.i + 1;
                    END WHILE;
                    CALL AddHeader();
                    THROW USERMESSAGE 'fail';
                  END;
                END MODULE;
                """;
        var function = new EsqlParser().parse(esql).function("Main").orElseThrow();
        // SET внутри IF не переводится: ветвлённый контекст неизвестен (FR-3.3)
        assertTrue(function.assignments().stream().noneMatch(
                a -> a.target().equals("OutputRoot.XMLNSC.Order.Status")));
        // при этом внешние SET сохраняются как есть
        assertTrue(function.assignments().stream().anyMatch(
                a -> a.target().equals("OutputRoot")));
        // IF/WHILE/CALL/THROW — честный непереводимый фрагмент каждого (FR-3.3)
        var fragments = function.unsupportedFragments();
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("IF InputBody.Order.Status")),
                "IF-оператор в unsupportedFragments: " + fragments);
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("WHILE InputRoot.Variables.i")),
                "WHILE в unsupportedFragments");
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("CALL AddHeader")),
                "CALL в unsupportedFragments");
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("THROW")),
                "THROW в unsupportedFragments");
        // внутренний SET ветвления не теряется молча — он виден в тексте фрагмента
        assertTrue(fragments.stream().anyMatch(f -> f.contains("OutputRoot.XMLNSC.Order.Status")));
        assertTrue(fragments.stream().anyMatch(f -> f.contains("InputRoot.Variables.i =")));
        assertEquals(4, fragments.size());
    }

    @Test
    void marksNonMainFunctionAndFilterModuleAsUnsupported() {
        String esql = """
                BROKER SCHEMA test
                CREATE COMPUTE MODULE WithHelper
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot = InputRoot;
                    RETURN TRUE;
                  END;
                  CREATE FUNCTION Helper() RETURNS INTEGER
                  BEGIN
                    RETURN 1;
                  END;
                END MODULE;
                """;
        var main = new EsqlParser().parse(esql).function("Main").orElseThrow();
        assertTrue(main.unsupportedFragments().stream()
                .noneMatch(f -> f.contains("Helper")),
                "заметки Helper не путаются с телом Main");
        // не-Main функции поднимаются генератором на статус модуля (через его issues)
        var bean = new EsqlToJavaGenerator().generate(new EsqlParser().parse(esql));
        assertTrue(bean.issues().stream().anyMatch(f -> f.contains("функция Helper")),
                "функция ≠ Main падает в issues модуля (статус не «конвертировано»)");
        assertFalse(bean.complete(), "модуль с функциями кроме Main не признаётся полным");

        String filterEsql = """
                BROKER SCHEMA test
                CREATE FILTER MODULE Router
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    RETURN TRUE;
                  END;
                END MODULE;
                """;
        var filterMain = new EsqlParser().parse(filterEsql).function("Main").orElseThrow();
        assertTrue(filterMain.unsupportedFragments().stream()
                        .anyMatch(f -> f.startsWith("FILTER-модуль")),
                "FILTER-модуль помечается непереводимым (нужен filter-предикат)");
    }
}
