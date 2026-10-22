package com.example.iib_camel.converter.esql;

import com.example.iib_camel.converter.report.UnitStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Перевод явно поддерживаемых SET; непонятная логика сохраняется в диагностике. */
public final class EsqlTranslator {

    private static final Pattern MQMD_TARGET =
            Pattern.compile("(?i)OUTPUTROOT\\.MQMD\\.(\\w+)");
    private static final Pattern BODY_TARGET =
            Pattern.compile("(?i)OUTPUTROOT\\.(XMLNSC|XML|XMLNS)\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");
    private static final Pattern CAST =
            Pattern.compile("(?i)CAST\\s*\\(\\s*(.+?)\\s+AS\\s+(\\w+)\\s*\\)");

    public record StatementTranslation(String esql, String java, UnitStatus status, String note) {
    }

    public record FunctionTranslation(String functionName,
                                      List<StatementTranslation> statements,
                                      List<String> issues) {

        public boolean complete() {
            return issues.isEmpty()
                    && statements.stream().allMatch(s -> s.status() == UnitStatus.CONVERTED);
        }
    }

    private final EsqlFunction function;

    public EsqlTranslator(EsqlFunction function) {
        this.function = function;
    }

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
        if (!function.returnsTrue()) {
            issues.add("Main: требуется завершающий RETURN TRUE; без дальнейших операторов");
        }
        if (!"BOOLEAN".equalsIgnoreCase(function.returnType())) {
            issues.add("Main: поддерживается только RETURNS BOOLEAN");
        }
        return new FunctionTranslation(function.name(), List.copyOf(statements), List.copyOf(issues));
    }

    private StatementTranslation translateAssignment(EsqlAssignment assignment) {
        String target = assignment.target();
        String value = assignment.value();

        if (isCopyWholeMessage(target, value)) {
            return new StatementTranslation(
                    original(assignment),
                    "copy(exchange);",
                    UnitStatus.CONVERTED,
                    "тело переносится без изменений (pass-through)");
        }

        Optional<String> literal = stringLiteral(value);
        if (literal.isEmpty()) {
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
        if (target.regionMatches(true, 0, "OutputRoot.JSON", 0, "OutputRoot.JSON".length())) {
            return unsupported(target, value, assignment, "домен JSON не поддерживается XML-runtime");
        }
        return unsupported(target, value, assignment,
                "цель вне переводимого подмножества (ожидается OutputRoot, OutputRoot.MQMD.* "
                + "или OutputRoot.<XMLNSC|XML|XMLNS>.<простой путь без namespaces/индексов>)");
    }

    private static boolean isCopyWholeMessage(String target, String value) {
        return value.equalsIgnoreCase("InputRoot")
                && target.equalsIgnoreCase("OutputRoot");
    }

    private static Optional<String> stringLiteral(String value) {
        String expression = value.strip();
        if (expression.length() < 2 || expression.charAt(0) != '\'') {
            return Optional.empty();
        }
        var literal = new StringBuilder();
        for (int i = 1; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c != '\'') {
                literal.append(c);
            } else if (i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                literal.append('\'');
                i++;
            } else {
                return i == expression.length() - 1 ? Optional.of(literal.toString()) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static StatementTranslation unsupported(String target, String value,
                                                    EsqlAssignment assignment, String reason) {
        return new StatementTranslation(
                original(assignment),
                "// TODO (FR-3.3): не переводится автоматически: SET " + target + " = " + value,
                UnitStatus.NOT_SUPPORTED,
                reason);
    }

    private static String original(EsqlAssignment assignment) {
        return "SET " + assignment.target() + " = " + assignment.value() + ";";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .replace("\b", "\\b").replace("\f", "\\f");
    }
}
