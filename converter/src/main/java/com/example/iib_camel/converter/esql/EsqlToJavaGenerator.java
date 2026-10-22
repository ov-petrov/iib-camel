package com.example.iib_camel.converter.esql;

import java.util.ArrayList;
import java.util.List;

/**
 * Генератор Java-бина Spring (runtime) из ESQL-модуля (FR-3.2 → артефакт для FR-R3.1).
 *
 * Контракт соответствия — как в runtime:
 *   bean id        = camelize(<модуль>),  Adapter_Compute1 → adapterCompute1;
 *   имя класса     = PascalCase того же id: AdapterCompute1;
 *   пакет          = com.example.iib_camel.runtime.generated;
 *   точка входа    = метод compute(Exchange) (аналог ESQL Main);
 *   зависимости    = {@link EsqlRuntimeSupport} (pass-through/MQMD/XML-дерево дорожки).
 *
 * Выход — исходный код для копирования в компилируемый проект runtime (Шаг 6
 * руководство по миграции); конвертер исходник не компилирует. Детерминированный (D-3):
 * обход функций и операторов — по порядку IR.
 */
public final class EsqlToJavaGenerator {

    /** Пакет, куда помещаются сгенерированные бины в runtime-проекте. */
    public static final String GENERATED_PACKAGE = "com.example.iib_camel.runtime.generated";

    /** Результат генерации одного модуля: файл + метаданные для отчёта (FR-5.1/5.2). */
    public record BeanResult(String className, String beanId, String java,
                             List<EsqlTranslator.StatementTranslation> statements,
                             boolean complete, List<String> issues) {
    }

    public BeanResult generate(EsqlModule module) {
        String beanId = camelize(module.name());
        String className = pascalize(module.name());
        EsqlFunction main = module.functions().stream()
                .filter(f -> f.name().equals("Main"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "модуль \"" + module.name() + "\": функция Main не найдена"));

        EsqlTranslator translator = new EsqlTranslator(main);
        EsqlTranslator.FunctionTranslation translation = translator.translate();
        // функции кроме Main — вне переводимого подмножества: статус модуля не «полный» (FR-3.3)
        List<String> moduleIssues = new ArrayList<>(translation.issues());
        for (EsqlFunction f : module.functions()) {
            if (!f.name().equals("Main")) {
                moduleIssues.add("функция " + f.name()
                        + ": функция кроме Main не переводится автоматически");
            }
        }

        boolean complete = translation.complete() && module.functions().size() == 1;

        var java = new StringBuilder();
        java.append("package ").append(GENERATED_PACKAGE).append(";\n\n")
            .append("// Сгенерировано конвертером iib-camel (FR-3.2, ESQL → Java); оригинал:\n")
            .append("// esql://routine/").append(module.schema().isBlank() ? "<schema>" : module.schema())
            .append('#').append(module.name()).append(".Main\n\n")
            .append("import com.example.iib_camel.runtime.compute.EsqlRuntimeSupport;\n")
            .append("import lombok.extern.slf4j.Slf4j;\n")
            .append("import org.apache.camel.Exchange;\n")
            .append("import org.springframework.stereotype.Component;\n\n")
            .append("/**\n")
            .append(" * Скомпилированный аналог Compute-модуля ").append(module.name())
            .append(" (сгенерировано, FR-3.2).\n")
            .append(" * Вызов из маршрута: bean:").append(beanId).append(".\n");
        if (!translation.complete() || module.functions().size() > 1) {
            java.append(" *\n * <p>ВНИМАНИЕ: операторы, помеченные TODO (FR-3.3), требуют\n")
                .append(" * ручной доработки — статус в отчёте миграции: «частично».\n");
        }
        java.append(" */\n")
            .append("@Slf4j\n")
            .append("@Component(\"").append(beanId).append("\")\n")
            .append("public class ").append(className).append(" {\n\n")
            .append("    private final EsqlRuntimeSupport esql = new EsqlRuntimeSupport();\n\n")
            .append("    /** Аналог Main(): перенос и правка сообщения; молчаливый возврат — успех. */\n")
            .append("    public void compute(Exchange exchange) {\n");

        for (EsqlTranslator.StatementTranslation statement : translation.statements()) {
            // оригинальный оператор сохраняем комментарием — трассируемость (FR-2.6)
            java.append("        // ").append(statement.esql()).append('\n')
                .append("        ").append(statement.java()).append('\n');
        }
        for (String issue : moduleIssues) {
            java.append("        // TODO (FR-3.3): ").append(issue).append('\n');
        }
        if (translation.statements().isEmpty() && translation.issues().isEmpty()) {
            java.append("        // модуль пуст: Main без операторов — тело сохраняется как есть\n");
        }

        java.append("    }\n}\n");

        return new BeanResult(className, beanId, java.toString(), translation.statements(),
                complete, List.copyOf(moduleIssues));
    }

    /** Adapter_Compute1 → adapterCompute1 (совместимо с bean-ссылками маршрутов). */
    public static String camelize(String module) {
        String[] parts = module.split("[_-]");
        var result = new StringBuilder(lowerFirst(parts[0]));
        for (int i = 1; i < parts.length; i++) {
            result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return result.toString();
    }

    private static String pascalize(String module) {
        String camel = camelize(module);
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
