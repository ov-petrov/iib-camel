package com.example.iib_camel.converter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты команды convert (L2, согласно docs/test-strategy.ru.md):
 * конвейер парсер → генератор → вывод + отчёт (FR-2.1, FR-5.1).
 */
class ConvertCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsCorpusFixtureAndWritesReport() throws Exception {
        Path input = copyFixture().resolve("corr-01-simple-adapter");
        Path output = tempDir.resolve("out");

        String golden = Files.readString(
                Path.of("src/test/resources/corpus/corr-01-simple-adapter/expected/Adapter-route.xml"),
                StandardCharsets.UTF_8);

        int exit = run(input, output);

        assertEquals(0, exit, "успешная конвертация → код 0");
        assertEquals(golden, Files.readString(output.resolve("Adapter-route.xml"),
                StandardCharsets.UTF_8), "маршрут побайтово равен golden-файлу");
        String report = Files.readString(output.resolve("migration-report.md"), StandardCharsets.UTF_8);
        assertTrue(report.contains("Adapter.msgflow"));
        assertTrue(report.contains("## Сводка"));
    }

    @Test
    void missingInputFailsWithNonZeroExit() {
        int exit = run(tempDir.resolve("no-such-dir"), tempDir.resolve("out"));
        assertTrue(exit != 0, "отсутствующий ввод → ненулевой код возврата");
    }

    private static int run(Path input, Path output) {
        return new CommandLine(new ConvertCommand())
                .execute("--input", input.toString(), "--output", output.toString());
    }

    /** Копирует фикстуру из src/test/resources в temp (команда работает с файловой системой). */
    private Path copyFixture() throws IOException {
        Path src = Paths.get("src/test/resources/corpus");
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.sorted(Comparator.comparing(p -> p.toString().replace('\\', '/'))).toList()) {
                Path target = tempDir.resolve("corpus").resolve(src.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        return tempDir.resolve("corpus");
    }
}
