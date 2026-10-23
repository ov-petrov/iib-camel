package ru.petrovov.iib_camel.converter;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import ru.petrovov.iib_camel.converter.cli.AnalyzeCommand;
import ru.petrovov.iib_camel.converter.cli.ConvertCommand;

@Command(name = "iib-camel-converter",
         mixinStandardHelpOptions = true,
         version = "iib-camel-converter 0.1.0-SNAPSHOT",
         description = "Миграция IIB → Spring Boot + Apache Camel: one-shot CLI конвертер.")
public final class ConverterApplication implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new ConverterApplication());
        commandLine.addSubcommand("analyze", new AnalyzeCommand());
        commandLine.addSubcommand("convert", new ConvertCommand());
        commandLine.setParameterExceptionHandler((ex, sub) -> {
            System.err.println("Ошибка аргументов: " + ex.getMessage());
            return 1;
        });
        System.exit(commandLine.execute(args.length == 0 ? new String[] {"convert"} : args));
    }
}
