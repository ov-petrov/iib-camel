package ru.petrovov.iib_camel.converter.mapping;

import org.w3c.dom.Element;
import ru.petrovov.iib_camel.converter.java.JavaNames;
import ru.petrovov.iib_camel.converter.schema.SchemaDiagnostic;
import ru.petrovov.iib_camel.converter.schema.SchemaModel;
import ru.petrovov.iib_camel.converter.schema.SchemaRegistry;
import ru.petrovov.iib_camel.converter.schema.SchemaXml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class GraphicalMapParser {
    private static final String NS = "http://www.ibm.com/2008/ccl/Mapping";
    private static final Pattern MESSAGE = Pattern.compile("mb:msg\\(([^,()]+),assembly,XMLNSC,Properties\\)");
    private static final Set<String> SCALAR_TYPES = Set.of("String", "Boolean", "Byte", "Short", "Integer", "Long",
            "Float", "Double", "java.math.BigInteger", "java.math.BigDecimal");

    public MappingResult parseMap(Path source, SchemaRegistry registry) {
        source = source.toAbsolutePath().normalize();
        try {
            Element root = SchemaXml.parse(source).getDocumentElement();
            require(NS.equals(root.getNamespaceURI()) && root.getLocalName().equals("mappingRoot"), "Expected IBM 2008 CCL mappingRoot");
            require(root.getAttribute("domainID").equals("com.ibm.msl.mapping.xml")
                    && root.getAttribute("domainIDExtension").equals("mb")
                    && root.getAttribute("version").equals("8.0.5.0")
                    && root.getAttribute("mainMap").equals("true"), "Unsupported IBM graphical mapping profile/version");
            attributes(root, Set.of("domainID", "domainIDExtension", "version", "mainMap", "targetNamespace"));
            children(root, Set.of("input", "output", "namespaces", "generation", "mappingDeclaration"));
            for (Element generation : named(root, "generation")) require(generation.getAttribute("engine").equals("xquery"), "Unsupported generation engine");
            Element declaration = one(root, "mappingDeclaration");
            attributes(declaration, Set.of("name"));
            children(declaration, Set.of("input", "output", "move", "documentation"));
            String name = declaration.getAttribute("name");
            require(!name.isBlank(), "Mapping declaration requires a name");
            Element input = one(declaration, "input");
            Element output = one(declaration, "output");
            attributes(input, Set.of("namespace", "path", "var"));
            attributes(output, Set.of("namespace", "path", "var"));
            require(SchemaXml.children(input).isEmpty() && SchemaXml.children(output).isEmpty(), "Casts and assembly refinements unsupported");
            String inputRoot = messageRoot(input);
            String outputRoot = messageRoot(output);
            SchemaModel inputModel = registry.resolve(schemaPath(source, one(root, "input")), input.getAttribute("namespace"), inputRoot);
            SchemaModel outputModel = registry.resolve(schemaPath(source, one(root, "output")), output.getAttribute("namespace"), outputRoot);
            List<String[]> mappings = new ArrayList<>();
            Set<String> targets = new HashSet<>();
            boolean propertiesCopied = false;
            for (Element move : named(declaration, "move")) {
                children(move, Set.of("input", "output", "documentation"));
                require(move.getAttributes().getLength() == 0, "Move refinements unsupported");
                Element from = one(move, "input");
                Element to = one(move, "output");
                attributes(from, Set.of("path"));
                attributes(to, Set.of("path"));
                require(SchemaXml.children(from).isEmpty() && SchemaXml.children(to).isEmpty(), "Move endpoint refinements unsupported");
                String fromPath = relativePath(from.getAttribute("path"), input, inputRoot);
                String toPath = relativePath(to.getAttribute("path"), output, outputRoot);
                if (fromPath.equals("@Properties") && toPath.equals("@Properties")) {
                    require(!propertiesCopied, "Duplicate Properties copy");
                    propertiesCopied = true;
                    continue;
                }
                ResolvedField sourceField = resolveField(inputModel, fromPath, registry);
                ResolvedField targetField = resolveField(outputModel, toPath, registry);
                require(sourceField.type().equals(targetField.type()) && SCALAR_TYPES.contains(sourceField.type()),
                        "Only same-type scalar field moves supported: " + fromPath + " -> " + toPath);
                require(targets.add(targetField.path()), "Multiple moves to target " + toPath);
                mappings.add(new String[]{sourceField.path(), targetField.path()});
            }
            require(!mappings.isEmpty(), "Map has no supported payload field moves");
            require(propertiesCopied, "XMLNSC profile requires an explicit Properties-to-Properties move; exchange headers are preserved");
            validateRequiredTargets(outputModel, "", targets, registry);
            String className = JavaNames.className(name) + "Mapper";
            String processorClassName = JavaNames.className(name) + "Processor";
            String beanName = JavaNames.propertyName(processorClassName);
            StringBuilder java = new StringBuilder("package " + GeneratedMapper.PACKAGE + ";\n\n")
                    .append("import org.mapstruct.Mapper;\nimport org.mapstruct.Mapping;\nimport org.mapstruct.BeanMapping;\n")
                    .append("import ").append(SchemaModel.PACKAGE).append(".*;\n\n")
                    .append("@Mapper(componentModel = \"spring\")\npublic interface ").append(className).append(" {\n")
                    .append("    @BeanMapping(ignoreByDefault = true)\n");
            for (String[] mapping : mappings) java.append("    @Mapping(source = \"").append(mapping[0]).append("\", target = \"").append(mapping[1]).append("\")\n");
            java.append("    ").append(outputModel.className()).append(" map(").append(inputModel.className()).append(" source);\n}\n");
            return new MappingResult(new GeneratedMapper(source, name, className, beanName,
                    inputModel.className(), outputModel.className(), java.toString(),
                    processorSource(processorClassName, beanName, className, inputModel, outputModel, registry)), List.of());
        } catch (Exception ex) {
            return new MappingResult(null, List.of(new SchemaDiagnostic(source, "MAPPING_UNSUPPORTED", ex.getMessage())));
        }
    }

    private static Path schemaPath(Path source, Element endpoint) throws Exception {
        attributes(endpoint, Set.of("path"));
        require(SchemaXml.children(endpoint).isEmpty(), "Inline schema endpoints unsupported");
        String value = endpoint.getAttribute("path");
        require(!value.isBlank() && !value.contains(":") && !value.contains("?"), "Only local XSD schema paths supported: " + value);
        require(value.endsWith(".xsd"), "XMLNSC mapping requires XSD schemas");
        Path path = source.getParent().resolve(value.startsWith("/") ? value.substring(1) : value).normalize();
        require(path.startsWith(source.getParent()) && Files.isRegularFile(path), "Missing or escaping schema path: " + value);
        require(path.toRealPath().startsWith(source.getParent().toRealPath()), "Schema symlink escapes integration project");
        return path;
    }

    private static String messageRoot(Element endpoint) {
        var matcher = MESSAGE.matcher(endpoint.getAttribute("path"));
        require(matcher.matches(), "Only XMLNSC message assembly roots supported: " + endpoint.getAttribute("path"));
        return matcher.group(1);
    }

    private static String relativePath(String path, Element endpoint, String root) {
        String variable = endpoint.getAttribute("var");
        if (path.startsWith("$")) {
            require(!variable.isBlank() && path.startsWith("$" + variable + "/"), "Unknown assembly variable: " + path);
            path = path.substring(variable.length() + 2);
        }
        if (path.equals("Properties")) return "@Properties";
        require(path.startsWith(root + "/"), "Expected payload root " + root + " in " + path);
        return path.substring(root.length() + 1);
    }

    private record ResolvedField(String path, String type) {}

    private static ResolvedField resolveField(SchemaModel model, String path, SchemaRegistry registry) {
        String[] segments = path.split("/", -1);
        List<String> java = new ArrayList<>();
        String type = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            SchemaModel.Field field = model.fields().stream().filter(f -> f.sourceName().equals(segment)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown/unsupported schema field " + path));
            java.add(field.javaName());
            type = field.javaType();
            if (i < segments.length - 1) {
                model = registry.modelType(type);
                require(model != null, "Field path descends into scalar " + path);
            }
        }
        return new ResolvedField(String.join(".", java), type);
    }

    private static List<Element> named(Element parent, String name) {
        return SchemaXml.children(parent).stream().filter(e -> name.equals(e.getLocalName())).toList();
    }

    private static Element one(Element parent, String name) {
        List<Element> children = named(parent, name);
        require(children.size() == 1, "Expected one " + name + " in " + parent.getLocalName());
        return children.getFirst();
    }

    private static void children(Element parent, Set<String> allowed) {
        for (Element element : SchemaXml.children(parent)) require(NS.equals(element.getNamespaceURI()) && allowed.contains(element.getLocalName()),
                "Unsupported mapping construct: " + element.getTagName());
    }

    private static void attributes(Element element, Set<String> allowed) {
        var attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            var attr = attrs.item(i);
            if (javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) continue;
            require(attr.getNamespaceURI() == null && allowed.contains(attr.getNodeName()),
                    "Unsupported mapping attribute " + attr.getNodeName() + " on " + element.getLocalName());
        }
    }

    private static String processorSource(String className, String beanName, String mapperClass,
                                          SchemaModel input, SchemaModel output, SchemaRegistry registry) {
        return "package " + GeneratedMapper.PACKAGE + ";\n\n"
                + "import org.apache.camel.Exchange;\nimport org.apache.camel.Handler;\n"
                + "import org.springframework.stereotype.Component;\n"
                + "import ru.petrovov.iib_camel.runtime.mapping.XmlBeanMapping;\n"
                + "import " + SchemaModel.PACKAGE + ".*;\n\n"
                + "@Component(\"" + beanName + "\")\npublic final class " + className + " {\n"
                + "    private final " + mapperClass + " mapper;\n"
                + "    private static final XmlBeanMapping.Model INPUT = " + modelDescriptor(input, input.rootName(), input.namespace(), registry) + ";\n"
                + "    private static final XmlBeanMapping.Model OUTPUT = " + modelDescriptor(output, output.rootName(), output.namespace(), registry) + ";\n\n"
                + "    public " + className + "(" + mapperClass + " mapper) { this.mapper = mapper; }\n\n"
                + "    @Handler\n    public void process(Exchange exchange) {\n"
                + "        Object body = exchange.getIn().getBody();\n"
                + "        if (!(body instanceof String xml)) throw new IllegalArgumentException(\"XMLNSC Mapping requires an XML String body\");\n"
                + "        " + input.className() + " source = (" + input.className() + ") XmlBeanMapping.read(xml, INPUT);\n"
                + "        String result = XmlBeanMapping.write(mapper.map(source), OUTPUT);\n"
                + "        exchange.getIn().setBody(result);\n    }\n}\n";
    }

    private static String modelDescriptor(SchemaModel model, String xmlName, String namespace, SchemaRegistry registry) {
        StringBuilder value = new StringBuilder("new XmlBeanMapping.Model(").append(quote(xmlName)).append(", ")
                .append(quote(namespace)).append(", ").append(model.className()).append(".class, java.util.List.of(");
        boolean first = true;
        for (SchemaModel.Field field : model.fields()) {
            if (!first) value.append(", ");
            first = false;
            SchemaModel nested = registry.modelType(field.javaType());
            value.append("new XmlBeanMapping.Field(").append(quote(field.sourceName())).append(", ")
                    .append(quote(field.namespace())).append(", ").append(quote(field.javaName())).append(", ")
                    .append(field.javaType()).append(".class, ").append(field.required()).append(", ").append(field.nillable())
                    .append(", ").append(nested == null ? "null" : modelDescriptor(nested, field.sourceName(), field.namespace(), registry)).append(')');
        }
        return value.append("))").toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static void validateRequiredTargets(SchemaModel model, String prefix, Set<String> targets, SchemaRegistry registry) {
        for (SchemaModel.Field field : model.fields()) {
            String path = prefix.isEmpty() ? field.javaName() : prefix + "." + field.javaName();
            SchemaModel nested = registry.modelType(field.javaType());
            if (nested == null) require(!field.required() || targets.contains(path), "Required output field is not mapped: " + path);
            else if (field.required() || targets.stream().anyMatch(target -> target.startsWith(path + "."))) {
                validateRequiredTargets(nested, path, targets, registry);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
