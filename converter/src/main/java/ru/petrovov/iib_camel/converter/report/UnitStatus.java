package ru.petrovov.iib_camel.converter.report;

public enum UnitStatus {

    CONVERTED("конвертировано"),

    PARTIAL("частично"),

    MANUAL("требует ручной доработки"),

    NOT_SUPPORTED("не поддерживается");

    private final String label;

    UnitStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static UnitStatus worst(UnitStatus a, UnitStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
