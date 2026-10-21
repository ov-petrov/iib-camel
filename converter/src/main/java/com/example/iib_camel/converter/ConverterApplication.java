package com.example.iib_camel.converter;

/**
 * One-shot CLI application: IIB artifacts converter.
 *
 * Запускается по команде, выполняет конвертацию указанных артефактов (.msgflow, .subflow, .esql
 * и др.) и завершается. Не оставляет фоновых процессов и не поднимает веб-служб.
 *
 * Функциональные требования: docs/functional-requirements.ru.md (раздел 3).
 */
public final class ConverterApplication {

    private ConverterApplication() {
    }

    public static void main(String[] args) {
        // TODO: реализовать разбор аргументов и конвейер конвертации
        // (см. docs/functional-requirements.ru.md, FR-7.1)
        System.out.println("iib-camel converter: CLI is not implemented yet.");
        System.out.println("Planned usage: converter --input <dir> --output <dir> [options]");
    }
}
