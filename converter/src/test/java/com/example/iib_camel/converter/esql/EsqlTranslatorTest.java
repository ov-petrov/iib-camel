package com.example.iib_camel.converter.esql;

import com.example.iib_camel.converter.report.UnitStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsqlTranslatorTest {

    @Test
    void translatesCopyAndMqmdStatements() {
        EsqlFunction main = mainOf("""
                BROKER SCHEMA check
                CREATE COMPUTE MODULE Check_Compute1
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot = InputRoot;
                    SET OutputRoot.MQMD.CommentField = 'CHECKED-BY-SUBFLOW';
                    RETURN TRUE;
                  END;
                END MODULE;
                """);
        var t = new EsqlTranslator(main).translate();

        assertTrue(t.complete(), "основной схеме corr-02 — полный перевод");
        assertEquals(2, t.statements().size());

        var copy = t.statements().get(0);
        assertEquals(UnitStatus.CONVERTED, copy.status());
        assertEquals("SET OutputRoot = InputRoot;", copy.esql());
        assertEquals("copy(exchange);", copy.java());

        var mqmd = t.statements().get(1);
        assertEquals("mqmd(exchange, \"CommentField\", \"CHECKED-BY-SUBFLOW\");", mqmd.java());
    }

    @Test
    void translatesBodyFieldAndCastStatements() {
        EsqlFunction main = mainOf("""
                BROKER SCHEMA adjust
                CREATE COMPUTE MODULE Adjust_Compute1
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot.XMLNSC.Order.Status = 'ACTIVE';
                    SET OutputRoot.XMLNSC.Order.Amount = CAST('250' AS DECIMAL);
                    RETURN TRUE;
                  END;
                END MODULE;
                """);
        var t = new EsqlTranslator(main).translate();

        assertTrue(t.complete(), "строковое поле тела и CAST-литерал входят в подмножество");
        assertEquals("setBodyField(exchange, \"Order.Status\", \"ACTIVE\");",
                t.statements().get(0).java());
        assertEquals("setBodyField(exchange, \"Order.Amount\", \"250\");",
                t.statements().get(1).java());
        assertTrue(t.statements().get(1).note().contains("CAST"));
        assertTrue(t.statements().get(1).note().contains("DECIMAL"));
    }

    @Test
    void unsupportedTargetAndComplexExpressionBecomeTodo() {
        EsqlFunction main = mainOf("""
                BROKER SCHEMA adjust
                CREATE COMPUTE MODULE Adjust_Compute1
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot.XMLNSC.Order.Total = InputRoot.Body.Amount * 2;
                    SET OutputRoot.Variables.Flag = 'X';
                    RETURN TRUE;
                  END;
                END MODULE;
                """);
        var t = new EsqlTranslator(main).translate();

        assertFalse(t.complete(), "выражение с умножением — не подмножество");
        assertEquals(UnitStatus.NOT_SUPPORTED, t.statements().get(0).status());
        assertTrue(t.statements().get(0).java().startsWith("// TODO (FR-3.3)"));
        assertEquals(2, t.issues().size(), "обе причины в issues");

        var rootVar = t.statements().get(1);
        assertEquals(UnitStatus.NOT_SUPPORTED, rootVar.status(),
                "цель вне OutputRoot/MQMD/домена — честный отказ");
    }

    @Test
    void issuesIncludeUnsupportedFragmentsFromParser() {
        EsqlFunction main = mainOf("""
                BROKER SCHEMA adjust
                CREATE COMPUTE MODULE Adjust_Compute1
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot = InputRoot;
                    IF InputBody.Order.Status = 'HOLD' THEN
                      SET OutputRoot.XMLNSC.Order.Status = 'PAUSED';
                    END IF;
                    RETURN TRUE;
                  END;
                END MODULE;
                """);
        var t = new EsqlTranslator(main).translate();

        assertFalse(t.complete(), "IF в теле — статус «частично», не полный");
        assertEquals(1, t.issues().size());
        assertTrue(t.issues().get(0).startsWith("IF InputBody.Order.Status"),
                "причина — сам фрагмент из парсера");
    }

        @Test
    void translatesEscapedApostrophesAndMultilineSemicolonLiterals() {
        var translation = translate("""
                SET OutputRoot.MQMD.CommentField = 'it''s; first
                second';
                RETURN TRUE;
                """);
        assertTrue(translation.complete());
        assertEquals("mqmd(exchange, \"CommentField\", \"it's; first\\nsecond\");",
                translation.statements().getFirst().java());
    }

    @Test
    void rejectsConcatenationInsteadOfTreatingItAsAString() {
        for (String value : java.util.List.of("'a' || 'b'", "'a' + 'b'", "CAST('a' || 'b' AS CHARACTER)")) {
            var translation = translate("SET OutputRoot.MQMD.CommentField = " + value + "; RETURN TRUE;");
            assertFalse(translation.complete(), value);
            assertEquals(UnitStatus.NOT_SUPPORTED, translation.statements().getFirst().status(), value);
        }
    }

    @Test
    void rejectsReturnFalsePropagateDeclarationsAndMissingReturn() {
        for (String body : java.util.List.of("RETURN FALSE;", "PROPAGATE; RETURN TRUE;",
                "DECLARE flag BOOLEAN TRUE; RETURN TRUE;", "SET OutputRoot = InputRoot;",
                "RETURN TRUE; SET OutputRoot = InputRoot;", "CALL Helper(); RETURN TRUE;")) {
            var translation = translate(body);
            assertFalse(translation.complete(), body);
            assertFalse(translation.issues().isEmpty(), body);
        }
    }

    @Test
    void rejectsUnsupportedDomainsAndStructuredPathsInsteadOfCallingXmlHelper() {
        for (String target : java.util.List.of("OutputRoot.JSON.Data.Status", "OutputRoot.XMLNSC.ns:Order.Status",
                "OutputRoot.XMLNSC.Order.Item[1]", "OutputRoot.NSC.Order.Status", "OutputRoot.NS.Order.Status")) {
            var translation = translate("SET " + target + " = 'value'; RETURN TRUE;");
            assertFalse(translation.complete(), target);
            assertEquals(UnitStatus.NOT_SUPPORTED, translation.statements().getFirst().status());
            assertFalse(translation.statements().getFirst().java().startsWith("setBodyField"));
        }
        var json = translate("SET OutputRoot.JSON.Data.Status = 'value'; RETURN TRUE;");
        assertTrue(json.issues().getFirst().contains("JSON"));
        var domainCopy = translate("SET OutputRoot.XMLNSC = InputRoot; RETURN TRUE;");
        assertFalse(domainCopy.complete());
        assertFalse(domainCopy.statements().getFirst().java().equals("copy(exchange);"));
    }

    @Test
    void databaseModuleDoesNotBecomeAComputeBean() {
        var function = mainOf("CREATE DATABASE MODULE Example CREATE FUNCTION Main() RETURNS BOOLEAN "
                + "BEGIN RETURN TRUE; END; END MODULE;");
        assertFalse(new EsqlTranslator(function).translate().complete());
        assertTrue(function.unsupportedFragments().stream().anyMatch(f -> f.contains("DATABASE")));
    }

    private static EsqlTranslator.FunctionTranslation translate(String body) {
        return new EsqlTranslator(mainOf("CREATE COMPUTE MODULE Example "
                + "CREATE FUNCTION Main() RETURNS BOOLEAN BEGIN\n" + body + "\nEND; END MODULE;")).translate();
    }

    private static EsqlFunction mainOf(String... unused) {
        return new EsqlParser().parse(String.join("\n", unused)).function("Main").orElseThrow();
    }
}
