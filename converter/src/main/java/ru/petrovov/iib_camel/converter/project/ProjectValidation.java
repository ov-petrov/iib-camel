package ru.petrovov.iib_camel.converter.project;

import ru.petrovov.iib_camel.converter.camel.RouteGenerator;
import ru.petrovov.iib_camel.converter.esql.EsqlModule;
import ru.petrovov.iib_camel.converter.esql.EsqlToJavaGenerator;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.report.Diagnosis;
import ru.petrovov.iib_camel.converter.report.UnitStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectValidation {
    private ProjectValidation() {}

    public static Map<Path, List<Diagnosis>> inspect(Map<Path, MsgFlow> flows, List<EsqlModule> modules) {
        var registry = new LinkedHashMap<String, MsgFlow>();
        flows.forEach((path, flow) -> {
            if (path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".subflow")) {
                registry.put(flow.name(), flow);
                registry.put(stem(flow.name()), flow);
                String alias = stem(path.getFileName().toString());
                if (!registry.containsKey(alias)) registry.put(alias, flow);
            }
        });
        var result = new LinkedHashMap<Path, List<Diagnosis>>();
        flows.forEach((path, flow) -> {
            List<Diagnosis> diagnoses = new ArrayList<>();
            try {
                RouteGenerator generator = new RouteGenerator(flow, registry);
                diagnoses.addAll((path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".subflow")
                        ? generator.inlineResult() : generator.generate()).diagnoses());
            } catch (RuntimeException e) {
                diagnoses.add(new Diagnosis("project", "Flow", UnitStatus.NOT_SUPPORTED, e.getMessage()));
            }
            for (var node : flow.nodes()) {
                if (!node.kind().equals("Compute")) continue;
                String expression = node.properties().getOrDefault("computeExpression", "");
                String issue = computeIssue(expression, modules);
                if (issue != null) diagnoses.add(new Diagnosis(node.id(), node.kind(), UnitStatus.NOT_SUPPORTED, issue));
            }
            for (var entry : flows.entrySet()) {
                if (!entry.getKey().equals(path) && entry.getValue().name().equals(flow.name())) {
                    diagnoses.add(new Diagnosis("project", "Flow", UnitStatus.NOT_SUPPORTED,
                            "повторяющееся имя потока: " + flow.name()));
                }
            }
            result.put(path, List.copyOf(diagnoses));
        });
        return result;
    }

    private static String computeIssue(String expression, List<EsqlModule> modules) {
        int hash = expression.indexOf('#');
        if (hash < 0) return "неразрешённая Compute-ссылка: " + expression;
        String schema = expression.substring(0, hash).replaceFirst("^esql://routine/", "");
        String[] routine = expression.substring(hash + 1).split("\\.", 2);
        if (routine.length > 1 && !routine[1].equalsIgnoreCase("Main")) return "поддерживается только Compute.Main";
        List<EsqlModule> matches = modules.stream().filter(m -> m.name().equals(routine[0]))
                .filter(m -> schema.isBlank() || schema.equals(m.schema())).toList();
        if (matches.size() != 1) return "Compute-ссылка не разрешена однозначно: " + expression;
        try {
            var bean = new EsqlToJavaGenerator().generate(matches.getFirst());
            return bean.complete() ? null : "ESQL не переведён полностью: " + String.join("; ", bean.issues());
        } catch (RuntimeException e) {
            return "ESQL: " + e.getMessage();
        }
    }

    private static String stem(String value) {
        return value.replaceFirst("(?i)\\.(msgflow|subflow)$", "");
    }
}
