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
        assertTrue(report.contains("## Статусы единиц"));
        assertTrue(report.contains("## Потоки"));
        assertTrue(report.contains("## Узлы по типам"));
        assertTrue(report.contains("## Детализация узлов"));
        assertTrue(report.contains("## ESQL"));
        assertTrue(report.contains("## Сводка"));
        assertTrue(report.contains("| **Всего** | 7 | 100.0 |"));
        assertTrue(report.contains("| Adapter_Compute1.esql | Adapter_Compute1 | 1 | 2 | конвертировано |"),
                "модуль ESQL получает статус перевода, а не безусловный MANUAL (FR-3.2)");
        assertTrue(report.contains("beans/AdapterCompute1.java"), "в примечании — сгенерированный бин");
        assertTrue(report.contains("OutputRoot.MQMD.ApplicationIdData → JMS-свойство"),
                "оператор SET MQMD переведён в JMS-свойство (примечание пооператорной таблицы)");
        Files.readString(output.resolve("beans/AdapterCompute1.java"));
        assertTrue(report.contains("| Input | MQInput | конвертировано | вход: jms:ADAPTER.IN |"));
        assertTrue(report.contains("Adapter_Compute1.esql"));
        assertTrue(report.contains("OutputRoot.MQMD.ApplicationIdData"));
    }

    @Test
    void missingInputFailsWithNonZeroExit() {
        int exit = run(tempDir.resolve("no-such-dir"), tempDir.resolve("out"));
        assertTrue(exit != 0, "отсутствующий ввод → ненулевой код возврата");
    }

    @Test
    void convertsCorr02SubflowFixtureWithHonestStatuses() throws Exception {
        Path input = copyFixture().resolve("corr-02-subflows");
        Path output = tempDir.resolve("out-corr02");

        int exit = run(input, output);

        assertEquals(0, exit, "подпоток найден во входном наборе → код 0");
        String golden = Files.readString(
                Path.of("src/test/resources/corpus/corr-02-subflows/expected/Order-route.xml"),
                StandardCharsets.UTF_8);
        assertEquals(golden, Files.readString(output.resolve("Order-route.xml")),
                "маршрут побайтово равен golden-файлу (родитель + вложенный direct-маршрут)");

        String report = Files.readString(output.resolve("migration-report.md"), StandardCharsets.UTF_8);
        assertTrue(report.contains("### Check.subflow"));
        assertTrue(report.contains("| CheckRef | Subflow | конвертировано | подпоток \"Check.subflow\""),
                "узел-ссылка получает статус «конвертировано» (direct-развёртывание)");
        assertTrue(report.contains("| TraceNode | Trace | не поддерживается |"),
                "Trace внутри подпотока честно помечен (FR-2.7, FR-5.2)");
        assertTrue(report.contains("| Check.subflow | direct: (вызов из родительского маршрута) |"));
        assertTrue(report.contains("- Подпотоков: 1 (развёрнуты во вложенные маршруты direct:, FR-2.2)"));
        assertTrue(report.contains("Check_Compute1.esql"), "ESQL подпотока в отчёте");
        assertTrue(Files.exists(output.resolve("beans/CheckCompute1.java")),
                "бин Compute-модуля подпотока сгенерирован в output/beans/");
        assertTrue(report.contains("| Check_Compute1.esql | Check_Compute1 | 1 | 2 | конвертировано |"),
                "модуль ESQL подпотока переведён полностью");
        assertTrue(report.contains("OutputRoot.MQMD.CommentField → JMS-свойство"),
                "MQMD-метка подпотока переведена пооператорно");
    }

    @Test
    void convertsCorr03EsqlMappingWithHonestPartialStatus() throws Exception {
        Path input = copyFixture().resolve("corr-03-esql-mapping");
        Path output = tempDir.resolve("out-corr03");

        int exit = run(input, output);

        assertEquals(0, exit);
        String golden = Files.readString(
                Path.of("src/test/resources/corpus/corr-03-esql-mapping/expected/Adjust-route.xml"),
                StandardCharsets.UTF_8);
        assertEquals(golden, Files.readString(output.resolve("Adjust-route.xml")));
        String goldenBean = Files.readString(
                Path.of("src/test/resources/corpus/corr-03-esql-mapping/expected/AdjustCompute1.java"),
                StandardCharsets.UTF_8);
        assertEquals(goldenBean, Files.readString(output.resolve("beans/AdjustCompute1.java"),
                StandardCharsets.UTF_8), "бин побайтово равен golden (D-3)");
        String goldenModel = Files.readString(
                Path.of("src/test/resources/corpus/corr-03-esql-mapping/expected/models/Order.java"),
                StandardCharsets.UTF_8);
        assertEquals(goldenModel, Files.readString(output.resolve("models/Order.java"),
                StandardCharsets.UTF_8), "модель побайтово равна golden");
        assertTrue(goldenModel.contains("private String Status;"));
        assertTrue(goldenModel.contains("private String Rating;"));

        String report = Files.readString(output.resolve("migration-report.md"), StandardCharsets.UTF_8);
        assertTrue(report.contains("| Adjust_Compute1.esql | Adjust_Compute1 | 1 | 4 | частично |"),
                "модуль с непереводимым ветвлением — статус «частично» (FR-3.3)");
        assertTrue(report.contains(
                "`SET OutputRoot.XMLNSC.Order.Rating = CAST('100' AS DECIMAL);` | конвертировано"),
                "CAST переводится пооператорно");
        assertTrue(report.contains("(тип DECIMAL)"), "тип CAST зафиксирован примечанием");
        assertTrue(report.contains("Непереводимые конструкции `Adjust_Compute1`"),
                "причины непереводимости поимённо в отчёте");
        assertTrue(report.contains("POJO-модели тела (FR-3.4, первый уровень) для `Adjust_Compute1`"),
                "модели перечислены в отчёте");
        assertTrue(report.contains("`models/Order.java`"), "файл модели виден в отчёте");
        assertTrue(goldenBean.contains("IF InputBody.Order.Status = 'HOLD'"),
                "непереводимое ветвление видно в бине (golden-сравнение бина выше)");
        assertTrue(report.contains("Требуют внимания: 2 из 5 единиц"),
                "ветвление блокирует модуль и использующий его Compute");
    }

    @Test
    void bareRunUsesDefaultInputAndOutputDirectories() throws Exception {
        Path input = tempDir.resolve("input");
        Path output = tempDir.resolve("output");
        Files.createDirectories(input);
        copyFixtureFiles(input);
        try {
            int exit = CliProcess.run(tempDir);
            assertEquals(0, exit, "запуск без аргументов: ./input → ./output");
            assertTrue(Files.exists(output.resolve("Adapter-route.xml")),
                    "дефолт --output: ./output");
            assertTrue(Files.readString(output.resolve("migration-report.md"),
                    StandardCharsets.UTF_8).contains("## Статусы единиц"));
        } finally {
            deleteRecursively(input);
            deleteRecursively(output);
        }
    }

    @Test
    void missingComputeModuleIsReportedAsManual() throws Exception {
        Path input = tempDir.resolve("in");
        Files.createDirectories(input);
        Files.copy(Path.of("src/test/resources/corpus/corr-01-simple-adapter/Adapter.msgflow"),
                input.resolve("Adapter.msgflow"));
        Path output = tempDir.resolve("out");
        assertEquals(0, run(input, output));
        String report = Files.readString(output.resolve("migration-report.md"));
        assertTrue(report.contains("| Enrich | Compute | требует ручной доработки |"));
        assertTrue(report.contains("неразрешённая Compute-ссылка"));
    }

    @Test
    void mainReferenceIsCaseInsensitive() throws Exception {
        Path input = copyFixture().resolve("corr-01-simple-adapter");
        Path flow = input.resolve("Adapter.msgflow");
        Files.writeString(flow, Files.readString(flow).replace("Adapter_Compute1.Main", "Adapter_Compute1.main"));
        Path output = tempDir.resolve("out");
        assertEquals(0, run(input, output));
        String report = Files.readString(output.resolve("migration-report.md"));
        assertTrue(report.contains("| Enrich | Compute | конвертировано |"));
    }

    @Test
    void successfulFlowAndBrokenEsqlReturnPartialSuccess() throws Exception {
        Path input = copyFixture().resolve("corr-01-simple-adapter");
        Files.writeString(input.resolve("Broken.esql"), "CREATE COMPUTE MODULE Broken");
        Path output = tempDir.resolve("out");
        assertEquals(2, run(input, output));
        assertTrue(Files.exists(output.resolve("Adapter-route.xml")));
        assertTrue(Files.readString(output.resolve("migration-report.md")).contains("Broken.esql"));
    }

    @Test
    void unsupportedControlFlowNeverGetsFullTranslation() throws Exception {
        Path input = copyFixture().resolve("corr-01-simple-adapter");
        Path esql = input.resolve("Adapter_Compute1.esql");
        String source = Files.readString(esql).replace("RETURN TRUE;", "RETURN FALSE;");
        Files.writeString(esql, source);
        Path output = tempDir.resolve("out");
        assertEquals(0, run(input, output));
        String report = Files.readString(output.resolve("migration-report.md"));
        assertTrue(report.contains("| Enrich | Compute | частично |"));
        assertTrue(report.contains("RETURN FALSE"));
        assertTrue(Files.readString(output.resolve("beans/AdapterCompute1.java"))
                .contains("UnsupportedOperationException"));
    }

    private static int run(Path input, Path output) {
        return new CommandLine(new ConvertCommand())
                .execute("--input", input.toString(), "--output", output.toString());
    }

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

    private static void copyFixtureFiles(Path to) throws IOException {
        Path src = Paths.get("src/test/resources/corpus/corr-01-simple-adapter");
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".msgflow") || name.endsWith(".esql");
            }).toList()) {
                Files.copy(file, to.resolve(file.getFileName().toString()));
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
