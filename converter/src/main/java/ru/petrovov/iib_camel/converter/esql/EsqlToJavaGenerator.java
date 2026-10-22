package ru.petrovov.iib_camel.converter.esql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EsqlToJavaGenerator {

    public static final String GENERATED_PACKAGE = "ru.petrovov.iib_camel.runtime.generated";
    public static final String GENERATED_MODEL_PACKAGE = GENERATED_PACKAGE + ".model";

    private static final Pattern SET_BODY_FIELD =
            Pattern.compile("(?s)^setBodyField\\(exchange, \\\"([^\\\"]+)\\\", \\\"(.*)\\\"\\);$");

    public record BeanResult(String className, String beanId, String java,
                             List<EsqlTranslator.StatementTranslation> statements,
                             List<ModelResult> models, boolean complete, List<String> issues) {
    }

    public record ModelResult(String className, String java) {
    }

    public BeanResult generate(EsqlModule module) {
        String beanId = camelize(module.name());
        String className = pascalize(module.name());
        EsqlFunction main = module.functions().stream()
                .filter(f -> f.name().equalsIgnoreCase("Main"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "модуль \"" + module.name() + "\": функция Main не найдена"));

        EsqlTranslator.FunctionTranslation translation = new EsqlTranslator(main).translate();
        List<String> moduleIssues = new ArrayList<>(translation.issues());
        for (EsqlFunction function : module.functions()) {
            if (!function.name().equalsIgnoreCase("Main")) {
                moduleIssues.add("функция " + function.name()
                        + ": функция кроме Main не переводится автоматически");
            }
        }
        if (module.functions().stream().filter(f -> f.name().equalsIgnoreCase("Main")).count() > 1) {
            moduleIssues.add("несколько функций Main в одном модуле");
        }
        boolean complete = moduleIssues.isEmpty() && translation.complete() && module.functions().size() == 1;

        var java = new StringBuilder();
        java.append("package ").append(GENERATED_PACKAGE).append(";\n\n")
            .append("// Источник: esql://routine/")
            .append(module.schema().isBlank() ? "<schema>" : module.schema())
            .append('#').append(module.name()).append(".Main\n\n")
            .append("import ru.petrovov.iib_camel.runtime.compute.AbstractComputeBean;\n")
            .append("import org.apache.camel.Exchange;\n")
            .append("import org.springframework.stereotype.Component;\n\n")
            .append("@Component(\"").append(beanId).append("\")\n")
            .append("public class ").append(className).append(" extends AbstractComputeBean {\n\n")
            .append("    @Override\n")
            .append("    protected void main(Exchange exchange) {\n");
        for (EsqlTranslator.StatementTranslation statement : translation.statements()) {
            appendComment(java, statement.esql(), "", "        ");
            if (complete) {
                java.append("        ").append(statement.java()).append('\n');
            }
        }
        for (String issue : moduleIssues) {
            appendComment(java, issue, "TODO (FR-3.3): ", "        ");
        }
        if (!complete) {
            java.append("        throw new UnsupportedOperationException(\"ESQL module ")
                .append(module.name()).append(": translation incomplete; see migration report\");\n");
        }
        java.append("    }\n}\n");
        return new BeanResult(className, beanId, java.toString(), translation.statements(),
                generateModels(translation.statements()), complete, List.copyOf(moduleIssues));
    }

    private static void appendComment(StringBuilder java, String source, String prefix, String indent) {
        for (String line : source.split("\\R", -1)) {
            java.append(indent).append("// ").append(prefix).append(line.replace("\\", "\\\\")).append('\n');
        }
    }

    public List<ModelResult> generateModels(List<EsqlTranslator.StatementTranslation> statements) {
        Map<String, ModelAccumulator> models = new LinkedHashMap<>();
        for (EsqlTranslator.StatementTranslation statement : statements) {
            Matcher matcher = SET_BODY_FIELD.matcher(statement.java());
            if (!matcher.matches()) {
                continue;
            }
            String path = matcher.group(1);
            String[] segments = path.split("\\.");
            String root = segments[0];
            String leafName = segments.length == 2 ? segments[1] : camelize(segments[segments.length - 1]);
            String className = pascalize(root);
            ModelAccumulator model = models.computeIfAbsent(className, k -> new ModelAccumulator(root));
            if (!model.root.equals(root)) {
                throw new IllegalArgumentException("разные корни ESQL " + model.root + " и " + root
                        + " нормализуются в одну модель " + className);
            }
            AggregatedField field = model.fields.get(leafName);
            if (field == null) {
                field = new AggregatedField(path);
                model.fields.put(leafName, field);
            } else if (!field.path.equals(path)) {
                throw new IllegalArgumentException("пути ESQL " + field.path + " и " + path
                        + " сворачиваются в поле " + className + "." + leafName);
            }
            field.sources.add(statement.esql());
        }
        List<ModelResult> result = new ArrayList<>();
        for (var entry : models.entrySet()) {
            String className = entry.getKey();
            var java = new StringBuilder();
            java.append("package ").append(GENERATED_MODEL_PACKAGE).append(";\n\n")
                .append("// Модель из ESQL-путей; типы String (FR-3.4).\n")
                .append("public class ").append(className).append(" {\n\n");
            for (var field : entry.getValue().fields.entrySet()) {
                for (String source : field.getValue().sources) {
                    appendComment(java, source, "", "    ");
                }
                java.append("    private String ").append(field.getKey()).append(";\n\n")
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

    private static final class ModelAccumulator {
        private final String root;
        private final Map<String, AggregatedField> fields = new LinkedHashMap<>();

        private ModelAccumulator(String root) {
            this.root = root;
        }
    }

    private static final class AggregatedField {
        private final String path;
        private final List<String> sources = new ArrayList<>();

        private AggregatedField(String path) {
            this.path = path;
        }
    }

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
