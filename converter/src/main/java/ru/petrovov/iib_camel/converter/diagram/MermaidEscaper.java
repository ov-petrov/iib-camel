package ru.petrovov.iib_camel.converter.diagram;

public final class MermaidEscaper {

    private MermaidEscaper() {
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\"", "&quot;")
                .replace("#", "&num;")
                .replace("|", "&#124;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');
    }
}
