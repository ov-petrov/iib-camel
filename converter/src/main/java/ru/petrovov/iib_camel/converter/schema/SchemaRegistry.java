package ru.petrovov.iib_camel.converter.schema;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record SchemaRegistry(List<SchemaModel> models, List<SchemaDiagnostic> diagnostics) {
    public SchemaRegistry { models = List.copyOf(models); diagnostics = List.copyOf(diagnostics); }

    public Map<String, String> javaSources() {
        return models.stream().collect(Collectors.toMap(SchemaModel::className, SchemaModel::javaSource,
                (a, b) -> { if (!a.equals(b)) throw new IllegalStateException("Conflicting model sources"); return a; }));
    }

    public SchemaModel resolve(Path source, String namespace, String root) {
        List<SchemaModel> matches = models.stream().filter(m -> m.source().equals(source.toAbsolutePath().normalize())
                && m.rootName().equals(root) && m.namespace().equals(namespace)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Schema root not resolved uniquely: " + source + " {" + namespace + "}" + root);
        return matches.getFirst();
    }

    public SchemaModel modelType(String javaType) {
        return models.stream().filter(m -> m.className().equals(javaType)).findFirst().orElse(null);
    }
}
