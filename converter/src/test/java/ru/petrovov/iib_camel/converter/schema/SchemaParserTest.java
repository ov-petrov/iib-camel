package ru.petrovov.iib_camel.converter.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SchemaParserTest {
    @TempDir Path temp;

    @Test void parsesTypedXsdJavaBeansAndNestedType() throws Exception {
        Path file = temp.resolve("model.xsd");
        Files.writeString(file, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:test" targetNamespace="urn:test">
                  <xs:complexType name="Details"><xs:sequence><xs:element name="active" type="xs:boolean"/></xs:sequence></xs:complexType>
                  <xs:element name="Order"><xs:complexType><xs:sequence>
                    <xs:element name="class" type="xs:string"/><xs:element name="count" type="xs:int"/>
                    <xs:element name="details" type="t:Details"/>
                  </xs:sequence></xs:complexType></xs:element>
                </xs:schema>
                """);
        SchemaRegistry result = new SchemaParser().parseSchemas(file);
        assertEquals(java.util.List.of(), result.diagnostics());
        Map<String, String> java = result.javaSources();
        assertTrue(java.get("Order").contains("private Integer count"));
        assertTrue(java.get("Order").contains("getClassValue()"));
        assertTrue(java.get("Order").contains("getDetails()"));
        assertTrue(java.get("OrderDetails").contains("Boolean getActive()"));
    }

    @Test void parsesJsonSchemaScalarsAndNestedObjects() throws Exception {
        Path file = temp.resolve("payment.schema.json");
        Files.writeString(file, """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","title":"Payment","type":"object",
                 "additionalProperties":false,"required":["total"],"properties":{
                   "total":{"type":"number"},"count":{"type":"integer"},
                   "details":{"type":"object","additionalProperties":false,"properties":{"active":{"type":"boolean"}}}}}
                """);
        SchemaRegistry result = new SchemaParser().parseSchemas(file);
        assertEquals(java.util.List.of(), result.diagnostics());
        assertTrue(result.javaSources().get("Payment").contains("java.math.BigDecimal total"));
        assertTrue(result.javaSources().get("Payment").contains("java.math.BigInteger count"));
        assertTrue(result.javaSources().get("PaymentDetails").contains("Boolean active"));
        Path sources = Files.createDirectories(temp.resolve("json-sources"));
        java.util.List<Path> files = new java.util.ArrayList<>();
        for (var model : result.models()) {
            Path java = sources.resolve(model.className() + ".java");
            Files.writeString(java, model.javaSource());
            files.add(java);
        }
        Path classes = Files.createDirectories(temp.resolve("json-classes"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, java.util.List.of("--release", "25", "-proc:none", "-d", classes.toString()),
                    null, manager.getJavaFileObjectsFromPaths(files)).call());
        }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            Class<?> payment = loader.loadClass(SchemaModel.PACKAGE + ".Payment");
            Object instance = payment.getConstructor().newInstance();
            payment.getMethod("setTotal", java.math.BigDecimal.class).invoke(instance, new java.math.BigDecimal("12.34"));
            assertEquals(new java.math.BigDecimal("12.34"), payment.getMethod("getTotal").invoke(instance));
            var total = java.util.Arrays.stream(java.beans.Introspector.getBeanInfo(payment).getPropertyDescriptors())
                    .filter(property -> property.getName().equals("total")).findFirst().orElseThrow();
            assertEquals(java.math.BigDecimal.class, total.getPropertyType());
            assertNotNull(total.getReadMethod());
            assertNotNull(total.getWriteMethod());
        }
    }

    @Test void rejectsUnknownConstraintsAndRepeatedElementsAtomically() throws Exception {
        Path xsd = temp.resolve("invalid.xsd");
        Files.writeString(xsd, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="Order"><xs:complexType><xs:sequence>
                <xs:element name="items" type="xs:string" maxOccurs="unbounded"/>
                </xs:sequence></xs:complexType></xs:element></xs:schema>
                """);
        assertFalse(new SchemaParser().parseSchemas(xsd).diagnostics().isEmpty());
        assertTrue(new SchemaParser().parseSchemas(xsd).models().isEmpty());
        Path json = temp.resolve("invalid.schema.json");
        Files.writeString(json, """
                {"type":"object","properties":{"code":{"type":"string","pattern":"[A-Z]+"}}}
                """);
        assertTrue(new SchemaParser().parseSchemas(json).diagnostics().getFirst().message().contains("pattern"));
    }

    @Test void rejectsExternalEntitiesAndNamingCollisions() throws Exception {
        Path file = temp.resolve("xxe.xsd");
        Files.writeString(file, "<!DOCTYPE schema SYSTEM 'file:///etc/passwd'><schema/>");
        assertTrue(new SchemaParser().parseSchemas(file).models().isEmpty());
        for (var entry : Map.of("a", "collision", "b", "Collision").entrySet()) Files.writeString(temp.resolve(entry.getKey() + ".schema.json"),
                "{\"title\":\"" + entry.getValue() + "\",\"type\":\"object\",\"properties\":{}}");
        SchemaRegistry result = new SchemaParser().parseSchemas(temp);
        assertTrue(result.models().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("SCHEMA_NAME_COLLISION")));
    }

    @Test void resolvesLocalIncludesAndImportsAndRejectsCyclesAndExternalLocations() throws Exception {
        Files.writeString(temp.resolve("types.xsd"), """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:types">
                <xs:complexType name="Details"><xs:sequence><xs:element name="active" type="xs:boolean"/></xs:sequence></xs:complexType>
                </xs:schema>
                """);
        Path main = temp.resolve("main.xsd");
        Files.writeString(main, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:types" targetNamespace="urn:main">
                <xs:import namespace="urn:types" schemaLocation="types.xsd"/>
                <xs:element name="Order"><xs:complexType><xs:sequence><xs:element name="details" type="t:Details"/></xs:sequence></xs:complexType></xs:element>
                </xs:schema>
                """);
        SchemaRegistry imported = new SchemaParser().parseSchemas(main);
        assertEquals(java.util.List.of(), imported.diagnostics());
        assertTrue(imported.javaSources().get("OrderDetails").contains("Boolean active"));
        Files.writeString(main, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:include schemaLocation="main.xsd"/></xs:schema>
                """);
        assertTrue(new SchemaParser().parseSchemas(main).diagnostics().getFirst().message().contains("Cyclic"));
        Files.writeString(main, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:include schemaLocation="https://example.com/schema.xsd"/></xs:schema>
                """);
        assertTrue(new SchemaParser().parseSchemas(main).diagnostics().getFirst().message().contains("local"));
    }
}
