package ru.petrovov.iib_camel.converter.esql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        assertTrue(function.assignments().stream().noneMatch(
                a -> a.target().equals("OutputRoot.XMLNSC.Order.Status")));

        assertTrue(function.assignments().stream().anyMatch(
                a -> a.target().equals("OutputRoot")));

        var fragments = function.unsupportedFragments();
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("IF InputBody.Order.Status")),
                "IF-оператор в unsupportedFragments: " + fragments);
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("WHILE InputRoot.Variables.i")),
                "WHILE в unsupportedFragments");
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("CALL AddHeader")),
                "CALL в unsupportedFragments");
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("THROW")),
                "THROW в unsupportedFragments");

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
    @Test
    void keepsEveryBranchOfNestedBlocksWithoutExtractingAssignments() {
        String block = """
                IF InputRoot.Flag = 'A' THEN
                  SET OutputRoot.MQMD.A = 'A';
                  SET OutputRoot.MQMD.B = 'B';
                ELSEIF InputRoot.Flag = 'B' THEN
                  CASE InputRoot.Kind
                    WHEN 'one' THEN
                      SET OutputRoot.MQMD.C = 'C';
                    ELSE
                      SET OutputRoot.MQMD.D = 'D';
                  END CASE;
                ELSE
                  WHILE TRUE DO
                    SET OutputRoot.MQMD.E = 'E';
                  END WHILE;
                END IF;
                """.strip();
        var function = main("SET OutputRoot = InputRoot;\n" + block + "\nRETURN TRUE;");

        assertEquals(1, function.assignments().size());
        assertEquals("OutputRoot", function.assignments().getFirst().target());
        assertEquals(java.util.List.of(block), function.unsupportedFragments());
        assertTrue(function.returnsTrue());
    }

    @Test
    void skipsCommentsAndPreservesQuotedSemicolonsAndFakeBlockTerminators() {
        var function = main("""
                -- SET OutputRoot.MQMD.Hidden = 'EXECUTED';
                /* CREATE COMPUTE MODULE Fake
                   SET OutputRoot.MQMD.HiddenAgain = 'EXECUTED'; END; END MODULE; */
                SET /* literal comment */ OutputRoot.MQMD.Visible = 'it''s; END; END MODULE; -- text';
                RETURN /* TRUE is outside this comment */ TRUE;
                """);
        assertEquals(1, function.assignments().size());
        assertEquals("OutputRoot.MQMD.Visible", function.assignments().getFirst().target());
        assertEquals("'it''s; END; END MODULE; -- text'", function.assignments().getFirst().value());
        assertTrue(function.unsupportedFragments().isEmpty());
        assertTrue(function.returnsTrue());
    }

    @Test
    void keepsCaseAndLoopBodiesAsWholeUnsupportedFragments() {
        for (String block : java.util.List.of(
                "CASE x WHEN 1 THEN SET OutputRoot.MQMD.A = 'A'; ELSE SET OutputRoot.MQMD.B = 'B'; END CASE;",
                "WHILE TRUE DO SET OutputRoot.MQMD.A = 'A'; SET OutputRoot.MQMD.B = 'B'; END WHILE;",
                "FOR item AS InputRoot.Items[] DO SET OutputRoot.MQMD.A = 'A'; END FOR;",
                "LOOP SET OutputRoot.MQMD.A = 'A'; END LOOP;",
                "REPEAT SET OutputRoot.MQMD.A = 'A'; UNTIL TRUE END REPEAT;",
                "BEGIN SET OutputRoot.MQMD.A = 'A'; END;")) {
            var function = main(block + "\nRETURN TRUE;");
            assertTrue(function.assignments().isEmpty(), block);
            assertEquals(java.util.List.of(block), function.unsupportedFragments(), block);
        }
    }

    @Test
    void doesNotExtractStatementsFromUnknownBlocksOrAfterEarlyReturn() {
        for (String body : java.util.List.of(
                "MYSTERY DO\n SET OutputRoot.MQMD.A = 'A'; SET OutputRoot.MQMD.B = 'B'; END MYSTERY; RETURN TRUE;",
                "RETURN TRUE; SET OutputRoot.MQMD.A = 'A';",
                "RETURN FALSE; SET OutputRoot.MQMD.A = 'A';")) {
            var function = main(body);
            assertTrue(function.assignments().isEmpty());
            assertFalse(function.unsupportedFragments().isEmpty());
            assertFalse(function.returnsTrue());
            assertTrue(function.unsupportedFragments().stream().anyMatch(f -> f.contains("OutputRoot.MQMD.A")));
        }
    }

    @Test
    void rejectsUnrecognizedOrUnterminatedSource() {
        for (String source : java.util.List.of("not ESQL", "BROKER SCHEMA alone", "/* never closed", "'never closed",
                module("IF TRUE THEN SET OutputRoot = InputRoot; RETURN TRUE;"),
                module("SET OutputRoot = InputRoot RETURN TRUE"), module("SET OutputRoot.MQMD.A = 'unterminated;"))) {
            assertThrows(IllegalArgumentException.class, () -> new EsqlParser().parseAll(source), source);
        }
        assertTrue(new EsqlParser().parseAll("-- comment only\n/* no modules */").isEmpty());
    }

    @Test
    void moduleDeclarationsOutsideFunctionsCannotDisappearFromDiagnostics() {
        var module = new EsqlParser().parse(module("RETURN TRUE;")
                .replace("CREATE FUNCTION", "DECLARE flag BOOLEAN TRUE;\nCREATE FUNCTION"));
        assertFalse(new EsqlToJavaGenerator().generate(module).complete());
        assertTrue(module.function("Main").orElseThrow().unsupportedFragments().stream()
                .anyMatch(f -> f.contains("DECLARE flag")));
    }

    private static EsqlFunction main(String body) {
        return new EsqlParser().parse(module(body)).function("Main").orElseThrow();
    }

    private static String module(String body) {
        return "CREATE COMPUTE MODULE Example CREATE FUNCTION Main() RETURNS BOOLEAN BEGIN\n"
                + body + "\nEND; END MODULE;";
    }

}
