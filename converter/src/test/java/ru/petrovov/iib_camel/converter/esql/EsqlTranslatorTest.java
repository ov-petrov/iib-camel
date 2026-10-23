package ru.petrovov.iib_camel.converter.esql;

import ru.petrovov.iib_camel.converter.report.UnitStatus;
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
        assertEquals("setBodyField(exchange, \"Order.Amount\", esqlCast(\"250\", \"DECIMAL\"));",
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

    @Test
    void validatesCastTypesAndLiteralLexicalForms() {
        for (String expression : java.util.List.of("CAST('12x' AS INTEGER)", "CAST('9223372036854775808' AS INTEGER)",
                "CAST('abc' AS DECIMAL)", "CAST('yes' AS BOOLEAN)", "CAST('12' AS DATE)",
                "CAST(1.5 AS INTEGER)", "CAST(CAST('12x' AS CHARACTER) AS INTEGER)", "CAST(TRUE AS DECIMAL)", "CAST(1 AS BOOLEAN)")) {
            var result = translate("SET OutputRoot.MQMD.Value = " + expression + "; RETURN TRUE;");
            assertFalse(result.complete(), expression);
            assertEquals(UnitStatus.NOT_SUPPORTED, result.statements().getFirst().status(), expression);
        }
        for (String expression : java.util.List.of("CAST('12' AS INTEGER)", "CAST('12.50' AS DECIMAL)",
                "CAST('TRUE' AS BOOLEAN)", "CAST(NULL AS INTEGER)", "TRUE", "NULL", "12", "12.50")) {
            assertTrue(translate("SET OutputRoot.MQMD.Value = " + expression + "; RETURN TRUE;").complete(), expression);
        }
    }

    @Test
    void preservesConditionalStructureAndRejectsUnknownBranchBeforeExecution() {
        var result = translate("""
                SET OutputRoot = InputRoot;
                IF CAST(InputRoot.XMLNSC.Order.Amount AS DECIMAL) >= 10.5 AND NOT FALSE THEN
                  SET OutputRoot.XMLNSC.Order.Status = 'HIGH';
                ELSEIF InputRoot.XMLNSC.Order.Amount IS NULL THEN
                  SET OutputRoot.XMLNSC.Order.Status = 'MISSING';
                ELSE
                  IF TRUE THEN SET OutputRoot.XMLNSC.Order.Status = 'LOW'; END IF;
                END IF;
                RETURN TRUE;
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(2, result.statements().size());
        assertTrue(result.statements().getLast().java().contains("} else {"));
        var bad = translate("IF TRUE THEN SET OutputRoot.MQMD.Value = 'ok'; ELSE MYSTERY; END IF; RETURN TRUE;");
        assertFalse(bad.complete());
        assertFalse(translate("IF InputRoot.XMLNSC.Order.Amount > 10 THEN SET OutputRoot = InputRoot; END IF; RETURN TRUE;").complete(),
                "mixed-type comparison requires explicit CAST");
    }

    private static EsqlTranslator.FunctionTranslation translate(String body) {
        return new EsqlTranslator(mainOf("CREATE COMPUTE MODULE Example "
                + "CREATE FUNCTION Main() RETURNS BOOLEAN BEGIN\n" + body + "\nEND; END MODULE;")).translate();
    }

    private static EsqlFunction mainOf(String... unused) {
        return new EsqlParser().parse(String.join("\n", unused)).function("Main").orElseThrow();
    }
}
