package ru.petrovov.iib_camel.converter.esql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Системный инвентарь ESQL «как есть» для analyze: вердикты по подмножеству FR-3.2,
 * честная строка ошибки разбора, детерминизм документа (D-3).
 */
class EsqlInventoryRendererTest {

    private static final String FULL_MODULE = """
            BROKER SCHEMA check
            CREATE COMPUTE MODULE Check_Compute1
              CREATE FUNCTION Main() RETURNS BOOLEAN
              BEGIN
                SET OutputRoot = InputRoot;
                SET OutputRoot.MQMD.CommentField = 'CHECKED-BY-SUBFLOW';
                RETURN TRUE;
              END;
            END MODULE;
            """;

    /** Вне подмножества: IF (ветвление) + функция кроме Main — значит «частично». */
    private static final String PARTIAL_MODULE = """
            BROKER SCHEMA adjust
            CREATE COMPUTE MODULE Adjust_Compute1
              CREATE FUNCTION Main() RETURNS BOOLEAN
              BEGIN
                SET OutputRoot = InputRoot;
                SET OutputRoot.XMLNSC.Order.Status = 'ADJUSTED';
                IF InputBody.Order.Status = 'HOLD' THEN
                  SET OutputRoot.XMLNSC.Order.Status = 'PAUSED';
                END IF;
                RETURN TRUE;
              END;
            END MODULE;
            """;

    /** Модуль без Main: вне переводимого подмножества по определению. */
    private static final String NO_MAIN_MODULE = """
            BROKER SCHEMA aux
            CREATE COMPUTE MODULE Detector_Compute1
              CREATE FUNCTION Helper() RETURNS BOOLEAN
              BEGIN
                SET OutputRoot = InputRoot;
                RETURN TRUE;
              END;
            END MODULE;
            """;

    private final EsqlInventoryRenderer renderer = new EsqlInventoryRenderer();

    private static EsqlInventoryRenderer.EsqlFileInfo file(String name, String esql) {
        return new EsqlInventoryRenderer.EsqlFileInfo(
                name, new EsqlParser().parseAll(esql), null);
    }

    @Test
    void verdictsForFullPartialAndNoMainModules() {
        var result = renderer.render(List.of(
                file("Check.esql", FULL_MODULE),
                file("Adjust.esql", PARTIAL_MODULE),
                file("Aux.esql", NO_MAIN_MODULE)));

        assertEquals(3, result.total());
        assertEquals(1, result.full());
        assertEquals(1, result.partial());
        assertEquals(1, result.outliers());

        assertTrue(result.markdown().contains(
                "| `Check.esql` | Check_Compute1 | COMPUTE | 1 | 2 | 0 | переводимо полностью |"));
        // IF уходит в issues (не оператор): 2 переведено, 1 конструкция вне подмножества
        assertTrue(result.markdown().contains(
                "| `Adjust.esql` | Adjust_Compute1 | COMPUTE | 1 | 2 | 1 | переводимо частично (2 операторов переведено) |"));
        assertTrue(result.markdown().contains(
                "| `Aux.esql` | Detector_Compute1 | COMPUTE | 1 | 0 | 1 | вне подмножества (Main не найдена) |"));
        assertTrue(result.markdown().contains("## Сводка переводимости"));
    }

    @Test
    void parseErrorGetsHonestRowAndCountsAsOutlier() {
        var broken = new EsqlInventoryRenderer.EsqlFileInfo(
                "Broken.esql", List.of(), "не найдено закрывающее \"END MODULE;\"");

        var result = renderer.render(List.of(broken));

        assertEquals(0, result.total(), "ошибка разбора — модулей не считаем");
        assertEquals(1, result.outliers());
        assertTrue(result.markdown().contains("ошибка разбора: не найдено закрывающее"),
                "причина ошибки видна в отчёте, а не теряется");
    }

    @Test
    void filterModuleWithoutMainIsMarked() {
        String esql = """
                BROKER SCHEMA aux
                CREATE FILTER MODULE SampleFilter
                  FILTER TRUE;
                END MODULE;
                """;
        var result = renderer.render(List.of(file("Filter.esql", esql)));

        assertEquals(1, result.outliers());
        assertTrue(result.markdown().contains("Main не найдена"),
                "FILTER-модуль честно помечен вне переводимого подмножества");
    }

    @Test
    void documentIsDeterministic() {
        String first = renderer.render(List.of(
                file("Adjust.esql", PARTIAL_MODULE), file("Check.esql", FULL_MODULE))).markdown();
        String second = renderer.render(List.of(
                file("Adjust.esql", PARTIAL_MODULE), file("Check.esql", FULL_MODULE))).markdown();
        assertEquals(first, second, "D-3: одинаковый вход — побайтово одинаковый отчёт");
    }
}
