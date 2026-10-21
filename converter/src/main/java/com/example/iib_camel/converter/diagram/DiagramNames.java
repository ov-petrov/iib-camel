package com.example.iib_camel.converter.diagram;

import java.util.Locale;

/**
 * Вычисление имён артефактов диаграмм (общая политика для CLI и форматов вывода):
 * имя артефакта диаграммы = имя IR (nsURI) без суффикса расширения;
 * fallback — имя файла-источника без расширения.
 */
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
