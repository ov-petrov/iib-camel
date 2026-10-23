package ru.petrovov.iib_camel.converter.diagram;

import java.util.Locale;

public final class DiagramNames {

    private DiagramNames() {
    }

    public static String of(String flowName, String fileStem) {
        String name = flowName == null ? "" : flowName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".msgflow")) {
            name = name.substring(0, name.length() - ".msgflow".length());
        } else if (lower.endsWith(".subflow")) {
            name = name.substring(0, name.length() - ".subflow".length());
        }
        return name.isBlank() ? fileStem : name;
    }
}
