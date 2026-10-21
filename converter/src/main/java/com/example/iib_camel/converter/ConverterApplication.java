package com.example.iib_camel.converter;

import com.example.iib_camel.converter.cli.ConvertCommand;
import com.example.iib_camel.converter.cli.DiagramCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Корневая точка входа конвертера: one-shot CLI (см. docs/functional-requirements.ru.md, FR-7.1).
 *
 * Запускается по команде, выполняет свою работу и завершается; фоновых процессов и веб-служб нет.
 * Подкоманды {@code diagram} (docs/diagram-renderer-design.ru.md) и {@code convert}
 * (FR-2.1…FR-2.7, FR-5.1) регистрируются в рантайме.
 */
@Command(name = "iib-camel-converter",
         mixinStandardHelpOptions = true,
         version = "iib-camel-converter 0.1.0-SNAPSHOT",
         description = "Миграция IIB → Spring Boot + Apache Camel: one-shot CLI конвертер.")
public final class ConverterApplication implements Runnable {

    @Override
    public void run() {
        // Приложение вызвано без подкоманды — печатаем справку
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new ConverterApplication());
        commandLine.addSubcommand("diagram", new DiagramCommand());
        commandLine.addSubcommand("convert", new ConvertCommand());
        commandLine.setParameterExceptionHandler((ex, sub) -> {
            // неверные аргументы → код возврата 1 (docs/diagram-renderer-design.ru.md, раздел 5)
            System.err.println("Ошибка аргументов: " + ex.getMessage());
            return 1;
        });
        System.exit(commandLine.execute(args));
    }
}
