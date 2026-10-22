package com.example.iib_camel.converter.esql;

import com.example.iib_camel.converter.report.UnitStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты перевода ESQL → Java (FR-3.2): конвертируемое подмножество
 * (copy/MQMD/тело/CAST) переводится; ветвления и вызовы — честный TODO (FR-3.3).
 */
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
        assertEquals("esql.copy(exchange);", copy.java());

        var mqmd = t.statements().get(1);
        assertEquals("esql.mqmd(exchange, \"CommentField\", \"CHECKED-BY-SUBFLOW\");", mqmd.java());
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
        assertEquals("esql.setBodyField(exchange, \"Order.Status\", \"ACTIVE\");",
                t.statements().get(0).java());
        assertEquals("esql.setBodyField(exchange, \"Order.Amount\", \"250\");",
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

    /** Явное сохранение нелитеральных SET (не в подмножестве) — это делает отказ — здесь цель. */
    private static EsqlFunction mainOf(String... unused) {
        return new EsqlParser().parse(String.join("\n", unused)).function("Main").orElseThrow();
    }
}
