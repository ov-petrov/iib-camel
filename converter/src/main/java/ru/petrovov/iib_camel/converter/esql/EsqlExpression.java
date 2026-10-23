package ru.petrovov.iib_camel.converter.esql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class EsqlExpression {
    enum Type { CHARACTER, INTEGER, DECIMAL, BOOLEAN, NULL }
    record Value(String java, Type type, Object constant, boolean literal) {}
    private final List<String> tokens = new ArrayList<>();
    private int position;

    static Value compile(String source) {
        EsqlExpression parser = new EsqlExpression(source);
        Value result = parser.or();
        if (parser.position != parser.tokens.size()) throw unsupported(source);
        return result;
    }

    private EsqlExpression(String source) {
        var matcher = Pattern.compile("\\s*(?:('(?:[^']|'')*')|([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)|([+-]?[0-9]+(?:\\.[0-9]+)?)|(<>|<=|>=|[()=<>]))").matcher(source);
        int end = 0;
        while (matcher.find()) {
            if (!source.substring(end, matcher.start()).isBlank()) throw unsupported(source);
            tokens.add(matcher.group().strip());
            end = matcher.end();
        }
        if (!source.substring(end).isBlank() || tokens.isEmpty()) throw unsupported(source);
    }

    private Value or() {
        Value left = and();
        while (take("OR")) left = logical("esqlOr", left, and());
        return left;
    }
    private Value and() {
        Value left = not();
        while (take("AND")) left = logical("esqlAnd", left, not());
        return left;
    }
    private Value not() {
        if (take("NOT")) {
            Value value = not();
            bool(value);
            return dynamic("esqlNot(" + value.java + ")", Type.BOOLEAN);
        }
        return comparison();
    }
    private Value comparison() {
        Value left = primary();
        if (take("IS")) {
            boolean negate = take("NOT");
            expect("NULL");
            return dynamic("(" + left.java + (negate ? " != null)" : " == null)"), Type.BOOLEAN);
        }
        if (position < tokens.size() && List.of("=", "<>", "<", "<=", ">", ">=").contains(tokens.get(position))) {
            String operator = tokens.get(position++);
            Value right = primary();
            boolean numeric = numeric(left.type) && numeric(right.type);
            if (left.type != Type.NULL && right.type != Type.NULL && left.type != right.type && !numeric)
                throw unsupported("mixed-type comparison; use explicit CAST");
            if ((left.type == Type.BOOLEAN || right.type == Type.BOOLEAN) && !List.of("=", "<>").contains(operator))
                throw unsupported("ordered BOOLEAN comparison");
            return dynamic("esqlCompare(" + left.java + ", " + right.java + ", \"" + operator + "\")", Type.BOOLEAN);
        }
        return left;
    }
    private Value primary() {
        if (take("(")) { Value value = or(); expect(")"); return value; }
        if (take("CAST")) {
            expect("(");
            Value value = primary();
            expect("AS");
            String type = next().toUpperCase(Locale.ROOT);
            Type target;
            try { target = Type.valueOf(type); } catch (IllegalArgumentException e) { throw unsupported("CAST AS " + type); }
            if (target == Type.NULL) throw unsupported("CAST AS NULL");
            expect(")");
            boolean supported = value.type == Type.NULL || value.type == target || target == Type.CHARACTER
                    || value.type == Type.CHARACTER || value.type == Type.INTEGER && target == Type.DECIMAL;
            if (!supported) throw unsupported("CAST " + value.type + " AS " + target);
            Object constant = value.literal ? castConstant(value.constant, target) : null;
            return new Value("esqlCast(" + value.java + ", \"" + type + "\")", target, constant, value.literal);
        }
        String token = next();
        if (token.startsWith("'")) {
            String value = token.substring(1, token.length() - 1).replace("''", "'");
            return new Value("\"" + escape(value) + "\"", Type.CHARACTER, value, true);
        }
        if (token.matches("[+-]?[0-9]+")) {
            long value;
            try { value = Long.parseLong(token); } catch (NumberFormatException e) { throw unsupported("INTEGER overflow: " + token); }
            return new Value("Long.valueOf(\"" + value + "\")", Type.INTEGER, value, true);
        }
        if (token.matches("[+-]?[0-9]+\\.[0-9]+")) {
            BigDecimal value = decimal(token);
            return new Value("new java.math.BigDecimal(\"" + value.toPlainString() + "\")", Type.DECIMAL, value, true);
        }
        if (token.equalsIgnoreCase("NULL") || token.equalsIgnoreCase("UNKNOWN")) return new Value("null", Type.NULL, null, true);
        if (token.equalsIgnoreCase("TRUE") || token.equalsIgnoreCase("FALSE"))
            return new Value(token.equalsIgnoreCase("TRUE") ? "Boolean.TRUE" : "Boolean.FALSE", Type.BOOLEAN, Boolean.valueOf(token), true);
        var path = Pattern.compile("(?i)(InputRoot|OutputRoot)\\.(XMLNSC|XMLNS)\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)").matcher(token);
        if (path.matches()) return dynamic("bodyField(exchange, " + path.group(1).equalsIgnoreCase("InputRoot") + ", \"" + path.group(3) + "\")", Type.CHARACTER);
        var header = Pattern.compile("(?i)(InputRoot|OutputRoot)\\.MQMD\\.([A-Za-z_][A-Za-z0-9_]*)").matcher(token);
        if (header.matches()) return dynamic("mqmdField(exchange, " + header.group(1).equalsIgnoreCase("InputRoot") + ", \"" + header.group(2) + "\")", Type.CHARACTER);
        throw unsupported(token);
    }
    private static Object castConstant(Object value, Type type) {
        if (value == null) return null;
        String text = value instanceof Boolean flag ? (flag ? "TRUE" : "FALSE") : value.toString();
        try {
            return switch (type) {
                case CHARACTER -> value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : text;
                case INTEGER -> {
                    if (!text.matches("[+-]?[0-9]+")) throw new IllegalArgumentException();
                    yield Long.parseLong(text);
                }
                case DECIMAL -> {
                    if (value instanceof Boolean) throw new IllegalArgumentException();
                    yield decimal(text);
                }
                case BOOLEAN -> {
                    if (!(value instanceof Boolean) && !text.equalsIgnoreCase("TRUE") && !text.equalsIgnoreCase("FALSE")) throw new IllegalArgumentException();
                    yield Boolean.valueOf(text);
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException e) { throw unsupported("invalid CAST('" + value + "' AS " + type + ")"); }
    }
    private Value logical(String method, Value left, Value right) {
        bool(left); bool(right);
        return dynamic(method + "(" + left.java + ", () -> " + right.java + ")", Type.BOOLEAN);
    }
    private static BigDecimal decimal(String text) {
        if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) throw unsupported("plain DECIMAL required");
        BigDecimal number = new BigDecimal(text);
        if (number.precision() > 34 || number.scale() > 34) throw unsupported("DECIMAL precision/scale exceeds profile limit 34");
        return number;
    }
    private static void bool(Value value) { if (value.type != Type.BOOLEAN && value.type != Type.NULL) throw unsupported("BOOLEAN condition required"); }
    static Value condition(String source) { Value value = compile(source); bool(value); return value; }
    private static boolean numeric(Type type) { return type == Type.INTEGER || type == Type.DECIMAL; }
    private static Value dynamic(String java, Type type) { return new Value(java, type, null, false); }
    private boolean take(String token) {
        if (position < tokens.size() && tokens.get(position).equalsIgnoreCase(token)) { position++; return true; }
        return false;
    }
    private void expect(String token) { if (!take(token)) throw unsupported("expected " + token); }
    private String next() { if (position == tokens.size()) throw unsupported("unexpected end"); return tokens.get(position++); }
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\b", "\\b").replace("\f", "\\f");
    }
    private static IllegalArgumentException unsupported(String text) { return new IllegalArgumentException("выражение вне переводимого подмножества: " + text); }
}
