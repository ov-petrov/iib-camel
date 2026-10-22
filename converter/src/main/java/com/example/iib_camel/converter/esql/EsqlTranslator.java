package com.example.iib_camel.converter.esql;

import com.example.iib_camel.converter.report.UnitStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Перевод ESQL-функции в Java-операторы бина (FR-3.2, первый автоматический уровень).
 *
 * Переводимое подмножество (базовый набор, типовые Compute-модули «перенести и пометить»):
 *
 *   SET OutputRoot = InputRoot;                        → esql.copy(exchange)  — тело переносится
 *                                                        как есть (QoS переносит camel-jms);
 *   SET OutputRoot.MQMD.'Field' = 'строка';            → esql.mqmd(exchange, "Field", "value")
 *                                                        (MQMD-поле → JMS-свойство сообщения);
 *   SET OutputRoot.XMLNSC.Path.To.Field = 'строка';    → esql.setBodyField(exchange,
 *                                                        "Path.To.Field", "value") —
 *                                                        динамическая правка XML-дерева;
 *   SET ... = CAST('x' AS ТИП);                        → литерал CAST как строка + примечание о типе.
 *
 * Всё прочее (ветвления, вызовы, сложные выражения) — TODO-заглушки с честной причиной:
 * статус оператора «не переведено» (см. отчёт convert, FR-3.3) — ничего не теряется молча.
 */
public final class EsqlTranslator {

    private static final Pattern MQMD_TARGET =
            Pattern.compile("(?i)OUTPUTROOT\\.MQMD\\.(\\w+)");
    private static final Pattern BODY_TARGET =
            Pattern.compile("(?i)OUTPUTROOT\\.(XMLNSC|XML|NS|NSC|JSON)\\.(.+)");
    private static final Pattern DOMAIN_ROOT =
            Pattern.compile("(?i)OUTPUTROOT(\\.\\w+)?");
    private static final Pattern CAST =
            Pattern.compile("(?i)CAST\\s*\\(\\s*(.+?)\\s+AS\\s+(\\w+)\\s*\\)");

    /** Один оператор перевода: исходный ESQL (трассируемость), Java-строка, статус, примечание. */
    public record StatementTranslation(String esql, String java, UnitStatus status, String note) {
    }

    /** Результат перевода функции: операторы + список причин непереводимости. */
    public record FunctionTranslation(String functionName,
                                      List<StatementTranslation> statements,
                                      List<String> issues) {

        /** Полный перевод: нет непереводимых операторов и неопознанных конструкций. */
        public boolean complete() {
            return issues.isEmpty()
                    && statements.stream().allMatch(s -> s.status() == UnitStatus.CONVERTED);
        }
    }

    private final EsqlFunction function;

    public EsqlTranslator(EsqlFunction function) {
        this.function = function;
    }

    /** Перевод тела функции (Main) — операторы бина + перечень непереводимых конструкций. */
    public FunctionTranslation translate() {
        List<StatementTranslation> statements = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (EsqlAssignment assignment : function.assignments()) {
            StatementTranslation t = translateAssignment(assignment);
            statements.add(t);
            if (t.status() == UnitStatus.NOT_SUPPORTED) {
                issues.add("SET " + assignment.target() + " = " + assignment.value()
                        + " — " + t.note());
            }
        }
        issues.addAll(function.unsupportedFragments());
        return new FunctionTranslation(function.name(), List.copyOf(statements), List.copyOf(issues));
    }

    private StatementTranslation translateAssignment(EsqlAssignment assignment) {
        String target = assignment.target();
        String value = assignment.value();

        // полная копия сообщения: тело переносится как есть
        if (isCopyWholeMessage(target, value)) {
            return new StatementTranslation(
                    original(assignment),
                    "copy(exchange);",
                    UnitStatus.CONVERTED,
                    "тело переносится без изменений (pass-through)");
        }

        Optional<String> literal = stringLiteral(value);
        if (literal.isEmpty()) {
            // CAST-выражение: литерал достаём, целевой тип фиксируем примечанием
            Matcher cast = CAST.matcher(value);
            if (cast.matches() && stringLiteral(cast.group(1)).isPresent()) {
                return setByTarget(target, stringLiteral(cast.group(1)).get(), assignment,
                        "CAST → строка" + (cast.group(2).isBlank() ? "" : " (тип " + cast.group(2) + ")"));
            }
            return unsupported(target, value, assignment,
                    "выражение вне переводимого подмножества (не строковая константа, не CAST)");
        }
        return setByTarget(target, literal.get(), assignment, null);
    }

    /** SET в один из трёх переводимых доменов: MQMD-свойство, поле тела XML, либо отказ. */
    private StatementTranslation setByTarget(String target, String value, EsqlAssignment assignment,
                                              String castNote) {
        Matcher mqmd = MQMD_TARGET.matcher(target);
        if (mqmd.matches()) {
            String note = "OutputRoot.MQMD." + mqmd.group(1);
            return new StatementTranslation(
                    original(assignment),
                    "mqmd(exchange, \"" + mqmd.group(1) + "\", \"" + escape(value) + "\");",
                    UnitStatus.CONVERTED,
                    castNote == null ? note + " → JMS-свойство"
                                     : note + " → JMS-свойство (" + castNote + ")");
        }
        Matcher body = BODY_TARGET.matcher(target);
        if (body.matches()) {
            String path = body.group(2);
            String note = "OutputRoot." + body.group(1) + "." + path;
            return new StatementTranslation(
                    original(assignment),
                    "setBodyField(exchange, \"" + path + "\", \"" + escape(value) + "\");",
                    UnitStatus.CONVERTED,
                    castNote == null ? note + " → поле тела XML"
                                     : note + " → поле тела XML (" + castNote + ")");
        }
        return unsupported(target, value, assignment,
                "цель вне переводимого подмножества (ожидается OutputRoot, OutputRoot.MQMD.* "
                + "или OutputRoot.<domain>.<path>)");
    }

    /** SET OutputRoot = InputRoot (именно корень, без доменных суффиксов кроме тривиальных). */
    private static boolean isCopyWholeMessage(String target, String value) {
        return value.equalsIgnoreCase("InputRoot")
                && DOMAIN_ROOT.matcher(target).matches()
                && !MQMD_TARGET.matcher(target).matches()
                && !BODY_TARGET.matcher(target).matches();
    }

    /** Строковый литерал ESQL: 'текст' (регулярно через DOTALL, чтобы не рвать многострочные). */
    private static Optional<String> stringLiteral(String value) {
        Matcher m = Pattern.compile("(?s)^'(.*)'$").matcher(value.strip());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static StatementTranslation unsupported(String target, String value,
                                                    EsqlAssignment assignment, String reason) {
        return new StatementTranslation(
                original(assignment),
                "// TODO (FR-3.3): не переводится автоматически: SET " + target + " = " + value,
        /** Правила перевода нет — TODO-заглушка в бине (FR-3.3). */
                UnitStatus.NOT_SUPPORTED,
                reason);
    }

    /** Исходный текст оператора с точкой с запятой — для комментария-трассируемости в бине. */
    private static String original(EsqlAssignment assignment) {
        return "SET " + assignment.target() + " = " + assignment.value() + ";";
    }

    /** Экранирование литерала для Java-исходника (кавычки и слэши). */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
