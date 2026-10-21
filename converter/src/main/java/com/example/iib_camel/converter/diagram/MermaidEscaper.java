package com.example.iib_camel.converter.diagram;

/**
 * Экранирование текста ярлыков узлов для Mermaid
 * (docs/diagram-renderer-design.ru.md, раздел 4.3).
 *
 * Порядок замен важен: сначала кавычки/решётка, затем "pipe" (его подстановка
 * содержит символ "#", поэтому выполняется после замены "#").
 * Собственные элементы разметки рендера (<br/>) не пропускаются через экранирование:
 * фрагменты пользователя экранируются отдельно, потом склеиваются.
 */
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
