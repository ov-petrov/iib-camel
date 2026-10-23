package ru.petrovov.iib_camel.converter.schema;

import ru.petrovov.iib_camel.converter.java.JavaNames;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class SchemaParser {
    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("string", "String"), Map.entry("boolean", "Boolean"),
            Map.entry("byte", "Byte"), Map.entry("short", "Short"), Map.entry("int", "Integer"),
            Map.entry("long", "Long"), Map.entry("integer", "java.math.BigInteger"),
            Map.entry("decimal", "java.math.BigDecimal"), Map.entry("float", "Float"),
            Map.entry("double", "Double"));

    public SchemaRegistry parseSchemas(Path input) {
        List<SchemaModel> models = new ArrayList<>();
        List<SchemaDiagnostic> diagnostics = new ArrayList<>();
        try {
            List<Path> paths;
            if (Files.isDirectory(input)) {
                try (var stream = Files.walk(input)) {
                    paths = stream.filter(Files::isRegularFile).filter(SchemaParser::isSchemaFile).sorted().toList();
                }
            } else paths = List.of(input);
            for (Path path : paths) {
                Path source = path.toAbsolutePath().normalize();
                List<SchemaModel> parsed = new ArrayList<>();
                try {
                    if (source.toString().endsWith(".xsd")) parseXsd(source, parsed);
                    else parseJson(source, parsed);
                    models.addAll(parsed);
                } catch (Exception ex) {
                    diagnostics.add(new SchemaDiagnostic(source, "SCHEMA_UNSUPPORTED", ex.getMessage()));
                }
            }
            Map<String, List<SchemaModel>> classes = new HashMap<>();
            for (SchemaModel model : models) classes.computeIfAbsent(model.className(), k -> new ArrayList<>()).add(model);
            Set<String> collisions = new HashSet<>();
            classes.forEach((name, group) -> {
                if (group.stream().map(model -> List.of(model.namespace(), model.rootName(), model.fields())).distinct().count() > 1) {
                    collisions.add(name);
                    group.forEach(model -> diagnostics.add(new SchemaDiagnostic(model.source(), "SCHEMA_NAME_COLLISION",
                            "Multiple schema models normalize to Java class " + name)));
                }
            });
            models.removeIf(model -> collisions.contains(model.className()));
        } catch (Exception ex) {
            diagnostics.add(new SchemaDiagnostic(input, "SCHEMA_READ_ERROR", ex.getMessage()));
        }
        return new SchemaRegistry(models, diagnostics);
    }

    private void parseXsd(Path source, List<SchemaModel> models) throws Exception {
        Element schema = SchemaXml.parse(source).getDocumentElement();
        require(XSD.equals(schema.getNamespaceURI()) && schema.getLocalName().equals("schema"), "Expected XML Schema root");
        String namespace = schema.getAttribute("targetNamespace");
        Map<String, Element> types = new LinkedHashMap<>();
        List<Element> roots = new ArrayList<>();
        collectSchema(source, source.getParent(), schema, namespace, types, roots, new HashSet<>(), new HashSet<>());
        Set<String> rootNames = new HashSet<>();
        for (Element root : roots) {
            validateElement(root);
            String name = root.getAttribute("name");
            require(rootNames.add(name), "Duplicate global element " + name);
            Element complex = complexType(root, types, namespace);
            require(complex != null, "Message root must have a complex type: " + name);
            xsdModel(source, namespace, name, JavaNames.className(name), complex, types, models, new HashSet<>());
        }
    }

    private void collectSchema(Path source, Path project, Element schema, String namespace, Map<String, Element> types,
                               List<Element> roots, Set<Path> visiting, Set<Path> visited) throws Exception {
        Path identity = source.toRealPath();
        require(visiting.add(identity), "Cyclic schema include/import: " + source);
        if (!visited.add(identity)) { visiting.remove(identity); return; }
        for (Element child : SchemaXml.children(schema)) {
            require(XSD.equals(child.getNamespaceURI()), "Foreign schema content " + child.getTagName());
            switch (child.getLocalName()) {
                case "annotation" -> { }
                case "complexType" -> {
                    String key = "{" + namespace + "}" + child.getAttribute("name");
                    require(!types.containsKey(key), "Duplicate complex type " + key);
                    types.put(key, child);
                }
                case "element" -> {
                    if (namespace.equals(schema.getAttribute("targetNamespace"))) roots.add(child);
                }
                case "include", "import" -> {
                    String location = child.getAttribute("schemaLocation");
                    require(!location.isBlank() && !location.contains(":") && !location.startsWith("/"), "Only relative local schema include/import supported");
                    Path dependency = source.getParent().resolve(location).normalize();
                    require(dependency.startsWith(project) && Files.isRegularFile(dependency)
                            && dependency.toRealPath().startsWith(project.toRealPath()), "Schema include/import escapes project or is missing: " + location);
                    Element referenced = SchemaXml.parse(dependency).getDocumentElement();
                    require(XSD.equals(referenced.getNamespaceURI()) && referenced.getLocalName().equals("schema"), "Referenced document is not XSD");
                    String declared = referenced.getAttribute("targetNamespace");
                    String expected = child.getLocalName().equals("include") ? namespace : child.getAttribute("namespace");
                    require(declared.equals(expected), "Schema include/import namespace mismatch: " + location);
                    List<Element> dependencyRoots = child.getLocalName().equals("include") ? roots : new ArrayList<>();
                    collectSchema(dependency, project, referenced, expected, types, dependencyRoots, visiting, visited);
                }
                default -> throw new IllegalArgumentException("Unsupported XSD construct: " + child.getLocalName());
            }
        }
        visiting.remove(identity);
    }

    private void xsdModel(Path source, String namespace, String rootName, String className, Element complex,
                          Map<String, Element> types, List<SchemaModel> models, Set<Element> visiting) {
        require(visiting.add(complex), "Recursive XSD types are unsupported");
        require(!complex.hasAttribute("abstract") || complex.getAttribute("abstract").equals("false"), "Abstract complex types unsupported");
        require(!complex.hasAttribute("mixed") || complex.getAttribute("mixed").equals("false"), "Mixed content is unsupported");
        List<Element> body = SchemaXml.children(complex).stream().filter(e -> !XSD.equals(e.getNamespaceURI()) || !e.getLocalName().equals("annotation")).toList();
        require(body.size() == 1 && XSD.equals(body.getFirst().getNamespaceURI())
                && body.getFirst().getLocalName().equals("sequence"), "Only complexType/sequence is supported");
        Element sequence = body.getFirst();
        require(!sequence.hasAttribute("maxOccurs") || sequence.getAttribute("maxOccurs").equals("1"), "Repeated sequences unsupported");
        require(!sequence.hasAttribute("minOccurs") || sequence.getAttribute("minOccurs").equals("1"), "Optional sequences unsupported");
        List<SchemaModel.Field> fields = new ArrayList<>();
        for (Element element : SchemaXml.children(sequence)) {
            if (XSD.equals(element.getNamespaceURI()) && element.getLocalName().equals("annotation")) continue;
            require(XSD.equals(element.getNamespaceURI()) && element.getLocalName().equals("element"), "Only scalar/nested element fields supported");
            validateElement(element);
            String field = element.getAttribute("name");
            String javaType = scalarType(element);
            if (javaType == null) {
                Element nested = complexType(element, types, namespace);
                require(nested != null, "Unsupported/unresolved field type " + element.getAttribute("type") + " for " + field);
                javaType = className + JavaNames.className(field);
                xsdModel(source, namespace, rootName + "/" + field, javaType, nested, types, models, visiting);
            }
            Element ownerSchema = element.getOwnerDocument().getDocumentElement();
            String form = element.hasAttribute("form") ? element.getAttribute("form") : ownerSchema.getAttribute("elementFormDefault");
            require(form.isEmpty() || Set.of("qualified", "unqualified").contains(form), "Unsupported element form " + form);
            String fieldNamespace = form.equals("qualified") ? ownerSchema.getAttribute("targetNamespace") : "";
            require(!element.hasAttribute("nillable") || Set.of("true", "false", "0", "1").contains(element.getAttribute("nillable")), "Invalid nillable");
            fields.add(new SchemaModel.Field(field, JavaNames.propertyName(field), javaType, fieldNamespace,
                    !element.getAttribute("minOccurs").equals("0"), Set.of("true", "1").contains(element.getAttribute("nillable"))));
        }
        validateFields(fields);
        models.add(new SchemaModel(source, namespace, rootName, className, fields));
        visiting.remove(complex);
    }

    private static void validateElement(Element element) {
        require(!element.getAttribute("name").isBlank(), "Elements require an explicit name; ref unsupported");
        require(!element.hasAttribute("ref") && !element.hasAttribute("substitutionGroup")
                && !element.hasAttribute("default") && !element.hasAttribute("fixed") && !element.hasAttribute("abstract"), "Element ref/substitution/default/fixed/abstract unsupported");
        require(!element.hasAttribute("maxOccurs") || element.getAttribute("maxOccurs").equals("1"), "Repeated elements unsupported: " + element.getAttribute("name"));
        require(!element.hasAttribute("minOccurs") || Set.of("0", "1").contains(element.getAttribute("minOccurs")), "Unsupported minOccurs");
        long inlineTypes = SchemaXml.children(element).stream().filter(e -> e.getLocalName().equals("complexType")).count();
        require(inlineTypes <= 1 && !(inlineTypes == 1 && element.hasAttribute("type")), "Element must specify exactly one inline or named type");
        for (Element child : SchemaXml.children(element)) {
            require(XSD.equals(child.getNamespaceURI()) && Set.of("annotation", "complexType").contains(child.getLocalName()),
                    "Unsupported element construct: " + child.getTagName());
        }
    }

    private static String scalarType(Element element) {
        String value = element.getAttribute("type");
        if (!value.contains(":")) return null;
        String[] parts = value.split(":", 2);
        return XSD.equals(element.lookupNamespaceURI(parts[0])) ? TYPES.get(parts[1]) : null;
    }

    private static Element complexType(Element element, Map<String, Element> types, String namespace) {
        List<Element> inline = SchemaXml.children(element).stream().filter(e -> e.getLocalName().equals("complexType")).toList();
        if (!inline.isEmpty()) return inline.getFirst();
        String value = element.getAttribute("type");
        String[] parts = value.split(":", 2);
        String uri = element.lookupNamespaceURI(parts.length == 2 ? parts[0] : null);
        return types.get("{" + (uri == null ? "" : uri) + "}" + parts[parts.length - 1]);
    }

    private void parseJson(Path source, List<SchemaModel> models) throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(source));
        require(schema.isObject(), "Expected JSON Schema object");
        String name = schema.path("title").asString("");
        if (name.isBlank()) name = source.getFileName().toString().replaceFirst("(?:\\.schema)?\\.json$", "");
        jsonModel(source, name, JavaNames.className(name), schema, models);
    }

    private void jsonModel(Path source, String rootName, String className, JsonNode schema, List<SchemaModel> models) {
        checkJsonKeys(schema, Set.of("$schema", "$id", "title", "description", "type", "properties", "required", "additionalProperties"));
        String dialect = schema.path("$schema").asString("");
        require(dialect.isBlank() || Set.of("http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft/2020-12/schema").contains(dialect), "Unsupported JSON Schema dialect " + dialect);
        require(schema.path("type").asString("").equals("object"), "Only JSON Schema object roots supported");
        require(schema.path("properties").isObject(), "JSON Schema properties must be an object");
        List<SchemaModel.Field> fields = new ArrayList<>();
        for (var property : schema.path("properties").properties()) {
            String type = property.getValue().path("type").asString("");
            String javaType = switch (type) {
                case "string" -> "String";
                case "boolean" -> "Boolean";
                case "integer" -> "java.math.BigInteger";
                case "number" -> "java.math.BigDecimal";
                case "object" -> className + JavaNames.className(property.getKey());
                default -> throw new IllegalArgumentException("Unsupported JSON Schema type for " + property.getKey() + ": " + type);
            };
            if (type.equals("object")) jsonModel(source, rootName + "/" + property.getKey(), javaType, property.getValue(), models);
            else checkJsonKeys(property.getValue(), Set.of("type", "title", "description"));
            fields.add(new SchemaModel.Field(property.getKey(), JavaNames.propertyName(property.getKey()), javaType));
        }
        JsonNode required = schema.path("required");
        require(required.isMissingNode() || required.isArray(), "required must be an array");
        if (required.isArray()) for (JsonNode name : required) require(name.isString() && schema.path("properties").has(name.asString()), "required references an unknown property");
        JsonNode additional = schema.path("additionalProperties");
        require(additional.isMissingNode() || (additional.isBoolean() && !additional.asBoolean()), "Open JSON objects unsupported; set additionalProperties=false");
        validateFields(fields);
        models.add(new SchemaModel(source, "", rootName, className, fields));
    }

    private static void checkJsonKeys(JsonNode node, Set<String> allowed) {
        require(node.isObject(), "Expected JSON Schema object");
        for (String key : node.propertyNames()) require(allowed.contains(key), "Unsupported JSON Schema keyword: " + key);
    }

    private static void validateFields(List<SchemaModel.Field> fields) {
        Set<String> names = new HashSet<>();
        for (SchemaModel.Field field : fields) require(names.add(field.javaName()), "Field name collision: " + field.sourceName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static boolean isSchemaFile(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".xsd") || name.endsWith(".schema.json")) return true;
        if (!name.endsWith(".json")) return false;
        try {
            return new ObjectMapper().readTree(Files.readString(path)).has("$schema");
        } catch (Exception ex) {
            return false;
        }
    }
}
