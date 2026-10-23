package ru.petrovov.iib_camel.converter.project;

import ru.petrovov.iib_camel.converter.mapping.GeneratedMapper;
import ru.petrovov.iib_camel.converter.mapping.GraphicalMapParser;
import ru.petrovov.iib_camel.converter.mapping.MappingResult;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.schema.SchemaParser;
import ru.petrovov.iib_camel.converter.schema.SchemaRegistry;
import ru.petrovov.iib_camel.converter.esql.EsqlTranslator;
import ru.petrovov.iib_camel.converter.java.JavaNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MappingProject(SchemaRegistry schemas, Map<Path, MappingResult> maps, List<String> errors) {
    public MappingProject {
        maps = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(maps));
        errors = List.copyOf(errors);
    }

    public static MappingProject load(Path input) {
        var schemas = Files.isDirectory(input) ? new SchemaParser().parseSchemas(input)
                : new SchemaRegistry(List.of(), List.of());
        var maps = new LinkedHashMap<Path, MappingResult>();
        var errors = new ArrayList<String>();
        schemas.diagnostics().forEach(d -> errors.add(d.source() + ": " + d.code() + ": " + d.message()));
        if (Files.isDirectory(input)) {
            try (var paths = Files.walk(input)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (name.endsWith(".map")) {
                        MappingResult result = new GraphicalMapParser().parseMap(path, schemas);
                        maps.put(path, result);
                        result.diagnostics().forEach(d -> errors.add(d.source() + ": " + d.message()));
                    } else if (name.endsWith(".mset") || name.endsWith(".mxsd") || name.endsWith(".dfdl")) {
                        errors.add(path + ": формат схемы не входит в профиль пилота");
                    }
                }
            } catch (IOException e) {
                errors.add(input + ": " + e.getMessage());
            }
        }
        return new MappingProject(schemas, maps, errors);
    }

    public List<GeneratedMapper> mappers() {
        return maps.values().stream().filter(MappingResult::supported).map(MappingResult::mapper).toList();
    }

    public List<String> validateBodyPaths(List<EsqlTranslator.StatementTranslation> statements) {
        var errors = new ArrayList<String>();
        var pattern = java.util.regex.Pattern.compile("setBodyField\\(exchange, \\\"([^\\\"]+)\\\",");
        for (var statement : statements) {
            var matcher = pattern.matcher(statement.java());
            while (matcher.find()) {
                String[] path = matcher.group(1).split("\\.");
                var model = schemas.modelType(JavaNames.className(path[0]));
                if (model == null) continue;
                boolean found = model.rootName().equals(path[0]);
                for (int i = 1; found && i < path.length; i++) {
                    String segment = path[i];
                    var field = model.fields().stream().filter(f -> f.sourceName().equals(segment)).findFirst().orElse(null);
                    found = field != null;
                    if (found && i < path.length - 1) {
                        model = schemas.modelType(field.javaType());
                        found = model != null;
                    }
                }
                if (!found) errors.add("ESQL-путь не соответствует модели схемы: " + matcher.group(1));
            }
        }
        return errors;
    }

    public MsgFlow bind(MsgFlow flow) {
        List<FlowNode> nodes = flow.nodes().stream().map(node -> {
            if (!node.kind().equals("Mapping")) return node;
            String expression = node.properties().getOrDefault("mappingExpression", "");
            String reference = expression.contains("#") ? expression.substring(expression.indexOf('#') + 1) : expression;
            List<GeneratedMapper> matches = mappers().stream().filter(mapper -> mapper.mapName().equals(reference)
                    || stem(mapper.source()).equals(reference)).toList();
            var properties = new LinkedHashMap<>(node.properties());
            if (matches.size() == 1) properties.put("mappingBean", matches.getFirst().beanName());
            else properties.put("mappingIssue", (matches.size() > 1 ? "неоднозначная" : "неразрешённая")
                    + " Mapping-ссылка: " + expression);
            return new FlowNode(node.id(), node.kind(), node.label(), properties, node.dynamicOutTerminals(), node.routeRules());
        }).toList();
        return new MsgFlow(flow.name(), nodes, flow.connections());
    }

    public String markdown() {
        StringBuilder out = new StringBuilder("\n## Схемы и Mapping\n\n");
        out.append("| Источник | Модель / mapper | Результат |\n|---|---|---|\n");
        schemas.models().forEach(model -> out.append("| ").append(cell(model.source().toString()))
                .append(" | ").append(model.className()).append(" | типизированная JavaBean-модель |\n"));
        maps.forEach((path, result) -> out.append("| ").append(cell(path.toString())).append(" | ")
                .append(result.supported() ? result.mapper().className() : "—").append(" | ")
                .append(result.supported() ? "MapStruct + XML processor" : "не поддерживается").append(" |\n"));
        if (!errors.isEmpty()) {
            out.append("\nОшибки схем/Mapping:\n\n");
            errors.forEach(error -> out.append("- ").append(cell(error)).append('\n'));
        }
        return out.toString();
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static String cell(String value) {
        return value.replace("|", "\\|").replace('\n', ' ').replace('\r', ' ');
    }
}
