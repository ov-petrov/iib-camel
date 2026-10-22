package com.example.iib_camel.converter.esql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Генератор Java-кода для runtime из ESQL-модуля (FR-3.2/FR-3.4 → артефакт для FR-R3.1).
 *
 * Контракт бина — как в runtime (FR-3.5 «узел ↔ метод»):
 *   bean id        = camelize(<модуль>),  Adapter_Compute1 → adapterCompute1;
 *   имя класса     = PascalCase того же id: AdapterCompute1;
 *   пакет          = com.example.iib_camel.runtime.generated;
 *   базовый класс  = AbstractComputeBean (protected copy/mqmd/setBodyField);
 *   точка входа    = метод compute(Exchange) (аналог ESQL Main).
 *
 * Дополнительно генерируются POJO-модели тела сообщения (FR-3.4, первый уровень):
 * по обращениям `SET OutputRoot.<domain>.<Path> = ...` восстанавливается структура
 * поддерева (класс на корень пути, поля String по листовым именам) — пакет
 * com.example.iib_camel.runtime.generated.model. Поведение бина правит тело динамически
 * (AbstractComputeBean.setBodyField), модель — наглядная основа для будущих мапперов FR-4.1.
 *
 * Выход — исходный код для копирования в компилируемый проект runtime (Шаг 6
 * руководство по миграции); конвертер исходник не компилирует. Детерминированный (D-3):
 * обход функций и операторов — по порядку IR.
 */
public final class EsqlToJavaGenerator {

    /** Пакет, куда помещаются сгенерированные бины в runtime-проекте. */
    public static final String GENERATED_PACKAGE = "com.example.iib_camel.runtime.generated";

    /** Пакет сгенерированных POJO-моделей тела (FR-3.4). */
    public static final String GENERATED_MODEL_PACKAGE = GENERATED_PACKAGE + ".model";

    private static final Pattern SET_BODY_FIELD =
            Pattern.compile("(?s)^setBodyField\\(exchange, \\\"([^\\\"]+)\\\", \\\"(.*)\\\"\\);$");

    /** Результат генерации одного модуля: файл + метаданные для отчёта (FR-5.1/5.2). */
    public record BeanResult(String className, String beanId, String java,
                             List<EsqlTranslator.StatementTranslation> statements,
                             List<ModelResult> models, boolean complete, List<String> issues) {
    }

    /** Сгенерированная POJO-модель тела (FR-3.4): имя файла и исходник. */
    public record ModelResult(String className, String java) {
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
            .append("import com.example.iib_camel.runtime.compute.AbstractComputeBean;\n")
            .append("import lombok.extern.slf4j.Slf4j;\n")
            .append("import org.apache.camel.Exchange;\n")
            .append("import org.springframework.stereotype.Component;\n\n")
            .append("/**\n")
            .append(" * Скомпилированный аналог Compute-модуля ").append(module.name())
            .append(" (сгенерировано, FR-3.2).\n")
            .append(" * Вызов из маршрута: bean:").append(beanId)
            .append("; поддержка операторов — protected-методы AbstractComputeBean.\n");
        if (!translation.complete() || module.functions().size() > 1) {
            java.append(" *\n * <p>ВНИМАНИЕ: операторы, помеченные TODO (FR-3.3), требуют\n")
                .append(" * ручной доработки — статус в отчёте миграции: «частично».\n");
        }
        java.append(" */\n")
            .append("@Slf4j\n")
            .append("@Component(\"").append(beanId).append("\")\n")
            .append("public class ").append(className).append(" extends AbstractComputeBean {\n\n")
            .append("    /** Аналог Main(): перенос и правка сообщения; молчаливый возврат — успех. */\n")
            .append("    @Override\n")
            .append("    protected void main(Exchange exchange) {\n");

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
                generateModels(translation.statements()), complete, List.copyOf(moduleIssues));
    }

    /**
     * POJO-модели из обращений к телу (FR-3.4, первый уровень): класс на корень пути,
     * поле String на листовое имя; более глубокие пути — сплющены в имя поля
     * (полный путь сохранён в javadoc — компромисс документа, честен в отчёте).
     */
    private List<ModelResult> generateModels(List<EsqlTranslator.StatementTranslation> statements) {
        Map<String, Map<String, List<String>>> models = new LinkedHashMap<>();
        for (EsqlTranslator.StatementTranslation st : statements) {
            Matcher m = SET_BODY_FIELD.matcher(st.java());
            if (!m.matches()) {
                continue;
            }
            String[] segments = m.group(1).split("\\.");
            String root = segments[0];
            String leafName = segments.length == 2
                    ? segments[1] : camelize(segments[segments.length - 1]);
            models.computeIfAbsent(root, k -> new LinkedHashMap<>())
                    .computeIfAbsent(leafName, k -> new ArrayList<>())
                    .add(segments.length == 2
                            ? st.esql()
                            : st.esql() + " (путь: OutputRoot.<domain>." + m.group(1) + ")");
        }
        List<ModelResult> result = new ArrayList<>();
        for (var entry : models.entrySet()) {
            String className = pascalize(entry.getKey());
            var java = new StringBuilder();
            java.append("package ").append(GENERATED_MODEL_PACKAGE).append(";\n\n")
                .append("/**\n")
                .append(" * Поддерево тела сообщения: ").append(className)
                .append(" (сгенерировано, FR-3.4).\n")
                .append(" * Поля восстановлены из обращений к дереву ESQL; типы пока String\n")
                .append(" * (компромисс: CAST выводится примечанием). Поведение маршрута правит\n")
                .append(" * тело динамически (AbstractComputeBean.setBodyField); класс — основа\n")
                .append(" * для будущих мапперов FR-4.1.\n")
                .append(" */\n")
                .append("public class ").append(className).append(" {\n\n");
            for (var field : entry.getValue().entrySet()) {
                java.append("    /** ").append(field.getValue().getFirst());
                for (int i = 1; i < field.getValue().size(); i++) {
                    java.append("\n     *  — переприсвоено: ").append(field.getValue().get(i));
                }
                java.append(" */\n")
                    .append("    private String ").append(field.getKey()).append(";\n\n")
                    .append("    public String ").append(field.getKey()).append("() {\n")
                    .append("        return ").append(field.getKey()).append(";\n")
                    .append("    }\n\n")
                    .append("    public void set").append(field.getKey())
                    .append("(String value) {\n")
                    .append("        this.").append(field.getKey()).append(" = value;\n")
                    .append("    }\n\n");
            }
            java.append("}\n");
            result.add(new ModelResult(className, java.toString()));
        }
        return List.copyOf(result);
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
