package ru.petrovov.iib_camel.converter.esql;

import ru.petrovov.iib_camel.converter.report.UnitStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EsqlTranslator {

    private static final Pattern MQMD_TARGET =
            Pattern.compile("(?i)OUTPUTROOT\\.MQMD\\.(\\w+)");
    private static final Pattern BODY_TARGET =
            Pattern.compile("(?i)OUTPUTROOT\\.(XMLNSC|XML|XMLNS)\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");

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
        for (EsqlStatement statement : function.statements()) {
            StatementTranslation t = translateStatement(statement);
            statements.add(t);
            if (t.status() == UnitStatus.NOT_SUPPORTED) {
                issues.add(t.esql() + " — " + t.note());
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

        EsqlExpression.Value expression;
        try {
            expression = EsqlExpression.compile(value);
        } catch (IllegalArgumentException e) {
            return unsupported(assignment, e.getMessage());
        }
        Matcher mqmd = MQMD_TARGET.matcher(target);
        if (mqmd.matches()) {
            return new StatementTranslation(original(assignment),
                    "mqmd(exchange, \"" + mqmd.group(1) + "\", " + expression.java() + ");",
                    UnitStatus.CONVERTED, "OutputRoot.MQMD." + mqmd.group(1) + " → JMS-свойство");
        }
        Matcher body = BODY_TARGET.matcher(target);
        if (body.matches()) {
            return new StatementTranslation(original(assignment),
                    "setBodyField(exchange, \"" + body.group(2) + "\", " + expression.java() + ");",
                    UnitStatus.CONVERTED, "OutputRoot." + body.group(1) + "." + body.group(2)
                            + " → поле XML" + (value.strip().toUpperCase(java.util.Locale.ROOT).startsWith("CAST") ? " (" + value + ")" : ""));
        }
        if (target.regionMatches(true, 0, "OutputRoot.JSON", 0, "OutputRoot.JSON".length())) {
            return unsupported(assignment, "домен JSON не поддерживается XML-runtime");
        }
        return unsupported(assignment, "цель вне переводимого подмножества");
    }

    private StatementTranslation translateStatement(EsqlStatement statement) {
        if (statement instanceof EsqlStatement.Assignment assignment) return translateAssignment(assignment.assignment());
        var conditional = (EsqlStatement.Conditional) statement;
        try {
            String condition = EsqlExpression.condition(conditional.condition()).java();
            var java = new StringBuilder("if (esqlTrue(" + condition + ")) {\n");
            appendBranch(java, conditional.whenTrue());
            if (!conditional.whenFalse().isEmpty()) {
                java.append("} else {\n");
                appendBranch(java, conditional.whenFalse());
            }
            java.append("}");
            return new StatementTranslation(conditional.source(), java.toString(), UnitStatus.CONVERTED, "IF/ELSE");
        } catch (IllegalArgumentException e) {
            return new StatementTranslation(conditional.source(), "",
                    UnitStatus.NOT_SUPPORTED, e.getMessage());
        }
    }

    private void appendBranch(StringBuilder java, List<EsqlStatement> branch) {
        for (EsqlStatement statement : branch) {
            StatementTranslation translated = translateStatement(statement);
            if (translated.status() != UnitStatus.CONVERTED) throw new IllegalArgumentException(translated.note());
            for (String line : translated.java().split("\\R")) java.append("    ").append(line).append('\n');
        }
    }

    public List<StatementTranslation> modelStatements() {
        List<StatementTranslation> result = new ArrayList<>();
        flatten(function.statements(), result);
        return List.copyOf(result);
    }

    private void flatten(List<EsqlStatement> statements, List<StatementTranslation> result) {
        for (EsqlStatement statement : statements) {
            if (statement instanceof EsqlStatement.Assignment assignment) result.add(translateAssignment(assignment.assignment()));
            else {
                var conditional = (EsqlStatement.Conditional) statement;
                flatten(conditional.whenTrue(), result);
                flatten(conditional.whenFalse(), result);
            }
        }
    }

    private static boolean isCopyWholeMessage(String target, String value) {
        return value.equalsIgnoreCase("InputRoot")
                && target.equalsIgnoreCase("OutputRoot");
    }

    private static StatementTranslation unsupported(EsqlAssignment assignment, String reason) {
        return new StatementTranslation(
                original(assignment),
            "",
                UnitStatus.NOT_SUPPORTED,
                reason);
    }

    private static String original(EsqlAssignment assignment) {
        return "SET " + assignment.target() + " = " + assignment.value() + ";";
    }

}
