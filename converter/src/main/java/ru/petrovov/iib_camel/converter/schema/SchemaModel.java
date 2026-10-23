package ru.petrovov.iib_camel.converter.schema;

import ru.petrovov.iib_camel.converter.java.JavaNames;
import java.nio.file.Path;
import java.util.List;

public record SchemaModel(Path source, String namespace, String rootName, String className,
                          List<Field> fields) {
    public static final String PACKAGE = "ru.petrovov.iib_camel.runtime.generated.model";

    public SchemaModel { fields = List.copyOf(fields); }

    public record Field(String sourceName, String javaName, String javaType, String namespace, boolean required, boolean nillable) {
        public Field(String sourceName, String javaName, String javaType) {
            this(sourceName, javaName, javaType, "", false, false);
        }
    }

    public String javaSource() {
        StringBuilder out = new StringBuilder("package " + PACKAGE + ";\n\npublic class " + className + " {\n");
        for (Field field : fields) {
            out.append("    private ").append(field.javaType()).append(' ').append(field.javaName()).append(";\n");
        }
        for (Field field : fields) {
            String suffix = JavaNames.accessorSuffix(field.javaName());
            out.append("\n    public ").append(field.javaType()).append(" get").append(suffix)
                    .append("() { return ").append(field.javaName()).append("; }\n")
                    .append("    public void set").append(suffix).append('(').append(field.javaType())
                    .append(" value) { this.").append(field.javaName()).append(" = value; }\n");
        }
        return out.append("}\n").toString();
    }
}
