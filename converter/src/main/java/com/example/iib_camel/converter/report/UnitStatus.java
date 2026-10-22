package com.example.iib_camel.converter.report;

/**
 * Статус единицы конвертации по FR-5.1: узел msgflow или модуль ESQL.
 * Порядок объявления — по возрастанию «серьёзности» (worst = максимальный ordinal).
 */
public enum UnitStatus {

    /** Правило конвертации применено полностью, заглушек нет. */
    CONVERTED("конвертировано"),

    /** Сгенерировано с TODO-заглушкой: не хватает свойства (очередь, фильтр, ESQL-ссылка). */
    PARTIAL("частично"),

    /** Правила автоматизации нет: код из ESQL формируется вручную (FR-3.x). */
    MANUAL("требует ручной доработки"),

    /** Правило конвертации не определено — создана TODO-заглушка (FR-2.7). */
    NOT_SUPPORTED("не поддерживается");

    private final String label;

    UnitStatus(String label) {
        this.label = label;
    }

    /** Русское название статуса — как в формулировке FR-5.1. */
    public String label() {
        return label;
    }

    /** Наиболее «серьёзный» из двух статусов. */
    public static UnitStatus worst(UnitStatus a, UnitStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
