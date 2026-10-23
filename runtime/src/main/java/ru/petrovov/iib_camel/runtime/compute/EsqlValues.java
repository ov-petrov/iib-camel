package ru.petrovov.iib_camel.runtime.compute;

import java.math.BigDecimal;
import java.util.Locale;

/** Three-valued ESQL operations for the explicit scalar conversion profile. */
public final class EsqlValues {
    private EsqlValues() {}

    public static Object cast(Object value, String type) {
        if (value == null) return null;
        String text = text(value);
        try {
            return switch (type.toUpperCase(Locale.ROOT)) {
                case "CHARACTER" -> value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : text;
                case "INTEGER" -> {
                    if (!text.matches("[+-]?[0-9]+")) throw new IllegalArgumentException("integer lexical form required");
                    yield Long.valueOf(text);
                }
                case "DECIMAL" -> {
                    if (value instanceof Boolean) throw new IllegalArgumentException("BOOLEAN to DECIMAL is unsupported");
                    if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))
                        throw new IllegalArgumentException("plain DECIMAL required");
                    BigDecimal number = new BigDecimal(text);
                    if (number.precision() > 34 || number.scale() > 34)
                        throw new IllegalArgumentException("DECIMAL precision/scale exceeds profile limit 34");
                    yield number;
                }
                case "BOOLEAN" -> {
                    if (!text.equalsIgnoreCase("TRUE") && !text.equalsIgnoreCase("FALSE"))
                        throw new IllegalArgumentException("TRUE or FALSE required");
                    yield Boolean.valueOf(text);
                }
                default -> throw new IllegalArgumentException("unsupported CAST target " + type);
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CAST('" + text + "' AS " + type + "): " + e.getMessage(), e);
        }
    }

    public static String text(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag ? "TRUE" : "FALSE";
        if (value instanceof BigDecimal number) return number.toPlainString();
        if (value instanceof String || value instanceof Long || value instanceof Integer) return value.toString();
        throw new IllegalArgumentException("Unsupported ESQL scalar type: " + value.getClass().getName());
    }

    public static Boolean compare(Object left, Object right, String operator) {
        if (left == null || right == null) return null;
        int order;
        if (left instanceof Number && right instanceof Number) {
            order = new BigDecimal(text(left)).compareTo(new BigDecimal(text(right)));
        } else if (left instanceof String a && right instanceof String b) {
            order = a.compareTo(b);
        } else if (left instanceof Boolean a && right instanceof Boolean b && (operator.equals("=") || operator.equals("<>"))) {
            order = a.compareTo(b);
        } else throw new IllegalArgumentException("ESQL comparison requires matching types or an explicit CAST");
        return switch (operator) {
            case "=" -> order == 0;
            case "<>" -> order != 0;
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            case ">" -> order > 0;
            case ">=" -> order >= 0;
            default -> throw new IllegalArgumentException("Unsupported comparison: " + operator);
        };
    }

    public static Boolean and(Object left, Object right) {
        Boolean a = booleanValue(left), b = booleanValue(right);
        if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) return Boolean.FALSE;
        return a == null || b == null ? null : Boolean.TRUE;
    }
    public static Boolean or(Object left, Object right) {
        Boolean a = booleanValue(left), b = booleanValue(right);
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) return Boolean.TRUE;
        return a == null || b == null ? null : Boolean.FALSE;
    }
    public static Boolean andLazy(Object left, java.util.function.Supplier<?> right) {
        Boolean flag = booleanValue(left);
        return Boolean.FALSE.equals(flag) ? Boolean.FALSE : and(flag, right.get());
    }
    public static Boolean orLazy(Object left, java.util.function.Supplier<?> right) {
        Boolean flag = booleanValue(left);
        return Boolean.TRUE.equals(flag) ? Boolean.TRUE : or(flag, right.get());
    }
    public static Boolean not(Object value) { Boolean flag = booleanValue(value); return flag == null ? null : !flag; }
    public static boolean truth(Object value) { return Boolean.TRUE.equals(booleanValue(value)); }
    private static Boolean booleanValue(Object value) {
        if (value == null || value instanceof Boolean) return (Boolean) value;
        throw new IllegalArgumentException("BOOLEAN condition required");
    }
}
