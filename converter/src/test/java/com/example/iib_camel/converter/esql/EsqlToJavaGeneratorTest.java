package com.example.iib_camel.converter.esql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты генератора бинов (FR-3.2): контракт bean-ссылок маршрутов
 * (beanId = camelize(модуль), метод compute(Exchange)), детерминизм, TODO-заглушки.
 */
class EsqlToJavaGeneratorTest {

    @Test
    void generatesBeanForCorr02Module() {
        var module = corr02Module();
        var bean = new EsqlToJavaGenerator().generate(module);

        assertEquals("CheckCompute1", bean.className());
        assertEquals("checkCompute1", bean.beanId());
        assertTrue(bean.complete(), "copy + MQMD — полный перевод");

        String java = bean.java();
        assertTrue(java.startsWith("package com.example.iib_camel.runtime.generated;\n"),
                "пакет сгенерированных бинов");
        assertTrue(java.contains("@Component(\"checkCompute1\")"),
                "bean-ссылка маршрута (bean:checkCompute1) разрешается аннотацией");
        assertTrue(java.contains("public class CheckCompute1"));
        assertTrue(java.contains("public void compute(Exchange exchange)"),
                "точка входа — аналог ESQL Main");
        // операторы с трассируемым оригиналом
        assertTrue(java.contains("// SET OutputRoot = InputRoot;\n        esql.copy(exchange);"));
        assertTrue(java.contains(
                "// SET OutputRoot.MQMD.CommentField = 'CHECKED-BY-SUBFLOW';\n"
                + "        esql.mqmd(exchange, \"CommentField\", \"CHECKED-BY-SUBFLOW\");"));
        assertFalse(java.contains("TODO"), "полный перевод — TODO нет");
    }

    @Test
    void deterministicGeneration() {
        var generator = new EsqlToJavaGenerator();
        assertEquals(generator.generate(corr02Module()).java(),
                generator.generate(corr02Module()).java(),
                "повторная генерация даёт побайтово тот же файл (D-3)");
    }

    @Test
    void unsupportedStatementsProduceTodoInBean() {
        String esql = """
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
                """;
        EsqlModule module = new EsqlParser().parse(esql);
        var bean = new EsqlToJavaGenerator().generate(module);

        assertFalse(bean.complete(), "IF → статус «частично»");
        assertTrue(bean.java().contains("// TODO (FR-3.3): IF InputBody.Order.Status"),
                "TODO-заглушка с причиной внутри compute()");
        assertTrue(bean.java().contains("ВНИМАНИЕ"),
                "javadoc предупреждает о ручной доработке");
    }

    @Test
    void missingMainFailsWithClearMessage() {
        String esql = """
                BROKER SCHEMA adjust
                CREATE COMPUTE MODULE NoMain
                  CREATE FUNCTION Helper() RETURNS INTEGER
                  BEGIN
                    RETURN 1;
                  END;
                END MODULE;
                """;
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new EsqlToJavaGenerator().generate(new EsqlParser().parse(esql)));
        assertTrue(ex.getMessage().contains("Main"), "причина отказа — нет функции Main");
    }

    private static EsqlModule corr02Module() {
        try {
            return new EsqlParser().parse(new String(
                    EsqlToJavaGeneratorTest.class.getResourceAsStream(
                            "/corpus/corr-02-subflows/Check_Compute1.esql").readAllBytes(),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
