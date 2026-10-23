package ru.petrovov.iib_camel.converter.java;

import java.beans.Introspector;
import java.text.Normalizer;
import java.util.Set;

public final class JavaNames {
    private static final Set<String> RESERVED_CLASSES = Set.of("String", "Boolean", "Byte", "Short", "Integer",
            "Long", "Float", "Double", "Object", "Class", "Enum", "Record", "Number",
            "AbstractComputeBean", "Exchange", "Component", "Mapper", "Mapping", "BeanMapping", "XmlBeanMapping");
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
            "null", "_", "var", "yield", "record", "sealed", "permits", "when");

    private JavaNames() {}

    public static String className(String source) {
        String name = upperFirst(propertyName(source));
        return RESERVED_CLASSES.contains(name) ? name + "Model" : name;
    }

    public static String propertyName(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("пустое имя Java");
        String[] words = Normalizer.normalize(source, Normalizer.Form.NFC).split("[^\\p{L}\\p{N}]+", -1);
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            name.append(name.isEmpty() ? Introspector.decapitalize(word) : upperFirst(word));
        }
        if (name.isEmpty()) throw new IllegalArgumentException("имя не содержит допустимых символов Java: " + source);
        if (!Character.isJavaIdentifierStart(name.codePointAt(0))) name.insert(0, "value");
        String result = name.toString();
        return RESERVED.contains(result) ? result + "Value" : result;
    }

    public static String accessorSuffix(String property) {
        String name = propertyName(property);
        if (name.length() > 1 && Character.isLowerCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return upperFirst(name);
    }

    public static String getterName(String property) {
        return "get" + accessorSuffix(property);
    }

    public static String setterName(String property) {
        return "set" + accessorSuffix(property);
    }

    private static String upperFirst(String value) {
        int first = value.codePointAt(0);
        return new StringBuilder().appendCodePoint(Character.toUpperCase(first))
                .append(value.substring(Character.charCount(first))).toString();
    }
}
