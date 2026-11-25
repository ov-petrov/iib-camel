package ru.petrovov.iib_camel.runtime_lite.xsd;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.annotation.XmlElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import javax.xml.transform.stream.StreamSource;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XsdToolTest {

    @TempDir
    Path temporary;

    @Test
    void generatesValidatedSamplesAndCompilableJakartaDtoFromLocalSchemaGraph() throws Exception {
        Path parts = Files.createDirectory(temporary.resolve("parts"));
        Files.writeString(parts.resolve("code.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:codes">
              <xs:simpleType name="Code">
                <xs:restriction base="xs:string">
                  <xs:length value="3"/>
                  <xs:pattern value="[A-Z]{3}"/>
                </xs:restriction>
              </xs:simpleType>
            </xs:schema>
            """);
        Files.writeString(parts.resolve("fields.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:sample">
              <xs:complexType name="Fields">
                <xs:choice>
                  <xs:element name="label" type="xs:string"/>
                  <xs:element name="count" type="xs:integer"/>
                </xs:choice>
              </xs:complexType>
            </xs:schema>
            """);
        Path entry = Files.writeString(temporary.resolve("entry.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:sample"
                       xmlns:s="urn:sample" xmlns:c="urn:codes" elementFormDefault="qualified">
              <xs:import namespace="urn:codes" schemaLocation="parts/code.xsd"/>
              <xs:include schemaLocation="parts/fields.xsd"/>
              <xs:element name="Envelope">
                <xs:annotation><xs:documentation>Schema documentation marker</xs:documentation></xs:annotation>
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="fields" type="s:Fields"/>
                    <xs:element name="code" type="c:Code"/>
                  </xs:sequence>
                  <xs:attribute name="status" type="xs:string" use="required" fixed="sample"/>
                </xs:complexType>
              </xs:element>
              <xs:element name="Receipt" type="xs:string"/>
            </xs:schema>
            """);
        Path output = temporary.resolve("generated");
        var result = run(entry, output, "--root", "{urn:sample}Envelope", "--root", "Receipt");
        assertEquals(0, result.exit(), result.diagnostics());
        var schema = SchemaInputs.load(entry).schema();
        Path sample = output.resolve("xml/Envelope.xml");
        schema.newValidator().validate(new StreamSource(sample.toFile()));
        schema.newValidator().validate(new StreamSource(output.resolve("xml/Receipt.xml").toFile()));
        assertTrue(Files.readString(sample).contains("status=\"sample\""));

        List<Path> sources;
        try (var files = Files.walk(output.resolve("dto"))) {
            sources = files.filter(file -> file.toString().endsWith(".java")).toList();
        }
        assertFalse(sources.isEmpty());
        for (Path source : sources) {
            assertFalse(Files.readString(source).contains("/**"), source.toString());
            assertFalse(Files.readString(source).contains("Schema documentation marker"), source.toString());
        }
        Path classes = Files.createDirectory(temporary.resolve("compiled"));
        Path bindingApi = Path.of(XmlElement.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var args = new ArrayList<>(List.of("--release", "21", "-classpath", bindingApi.toString(), "-d", classes.toString()));
        sources.forEach(source -> args.add(source.toString()));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("ru.petrovov.iib_camel.runtime_lite.generated.Envelope");
            var context = JAXBContext.newInstance(type);
            var value = context.createUnmarshaller().unmarshal(new StreamSource(sample.toFile()), type);
            var marshalled = new StringWriter();
            context.createMarshaller().marshal(value, marshalled);
            schema.newValidator().validate(new StreamSource(new StringReader(marshalled.toString())));
        }
    }

    @Test
    void publishesNothingForAnUnknownRootAndPreservesExistingOutput() throws Exception {
        Path entry = simpleSchema();
        Path output = temporary.resolve("generated");
        var missing = run(entry, output, "--root", "Missing");
        assertNotEquals(0, missing.exit());
        assertTrue(missing.diagnostics().contains("Missing"), missing.diagnostics());
        assertFalse(Files.exists(output));

        assertEquals(0, run(entry, output, "--mode", "xml").exit());
        Path sample = output.resolve("xml/Sample.xml");
        String content = Files.readString(sample);
        assertNotEquals(0, run(entry, output).exit());
        assertEquals(content, Files.readString(sample));
        assertFalse(Files.exists(output.resolve("dto")));
    }

    @Test
    void rejectsDtdAndNetworkSchemaLocationsBeforeWriting() throws Exception {
        Path output = temporary.resolve("generated");
        Path entry = Files.writeString(temporary.resolve("entry.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:include schemaLocation="http://localhost:9/schema.xsd"/>
            </xs:schema>
            """);
        var remote = run(entry, output, "--mode", "dto");
        assertNotEquals(0, remote.exit());
        assertTrue(remote.diagnostics().contains("relative schema locations"), remote.diagnostics());
        Files.writeString(entry, """
            <!DOCTYPE xs:schema []>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>
            """);
        assertNotEquals(0, run(entry, output, "--mode", "dto").exit());
        assertFalse(Files.exists(output));
    }

    @Test
    void protectsArtifactsThatDifferOnlyInCase() throws Exception {
        Files.createFile(temporary.resolve("case-probe"));
        boolean caseSensitive = Files.notExists(temporary.resolve("CASE-PROBE"));
        Path entry = Files.writeString(temporary.resolve("entry.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="Sample" type="xs:string"/>
              <xs:element name="sample" type="xs:string"/>
            </xs:schema>
            """);
        Path xmlOutput = temporary.resolve("samples");
        var xml = run(entry, xmlOutput, "--mode", "xml", "--root", "Sample", "--root", "sample");
        if (caseSensitive) {
            assertEquals(0, xml.exit(), xml.diagnostics());
            assertTrue(Files.exists(xmlOutput.resolve("xml/Sample.xml")));
            assertTrue(Files.exists(xmlOutput.resolve("xml/sample.xml")));
        } else {
            assertNotEquals(0, xml.exit());
            assertFalse(Files.exists(xmlOutput));
        }

        Files.writeString(entry, """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="ORDER"><xs:sequence/></xs:complexType>
              <xs:complexType name="Order"><xs:sequence/></xs:complexType>
              <xs:element name="Root" type="ORDER"/>
              <xs:element name="Tail" type="Order"/>
            </xs:schema>
            """);
        Path dtoOutput = temporary.resolve("sources");
        var dto = run(entry, dtoOutput, "--mode", "dto");
        if (caseSensitive) {
            assertEquals(0, dto.exit(), dto.diagnostics());
            var dtoPackage = dtoOutput.resolve("dto/ru/petrovov/iib_camel/runtime_lite/generated");
            assertTrue(Files.exists(dtoPackage.resolve("ORDER.java")));
            assertTrue(Files.exists(dtoPackage.resolve("Order.java")));
        } else {
            assertNotEquals(0, dto.exit());
            assertFalse(Files.exists(dtoOutput));
        }
    }

    @Test
    void generatesBatchForIndependentEntriesInAnExistingOutputDirectory() throws Exception {
        Path input = Files.createDirectory(temporary.resolve("input"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        Files.writeString(input.resolve("shared.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Value"><xs:sequence><xs:element name="label" type="Label"/></xs:sequence></xs:complexType>
            </xs:schema>
            """);
        Path first = Files.writeString(input.resolve("entry-a.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:include schemaLocation="shared.xsd"/>
              <xs:simpleType name="Label"><xs:restriction base="xs:string"/></xs:simpleType>
              <xs:element name="First" type="Value"/>
              <xs:element name="Second" type="xs:string"/>
            </xs:schema>
            """);
        Path second = Files.writeString(input.resolve("entry-b.XSD"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:complexType name="Value"><xs:sequence><xs:element name="flag" type="xs:boolean"/></xs:sequence></xs:complexType>
              <xs:element name="First" type="Value"/>
            </xs:schema>
            """);
        String[] arguments = {"--input", input.toString(), "--out", output.toString()};

        var generated = run(arguments);

        assertEquals(0, generated.exit(), generated.diagnostics());
        assertFalse(Files.exists(output.resolve("shared")));
        assertTrue(Files.exists(output.resolve("entry-a/dto/ru/petrovov/iib_camel/runtime_lite/generated/entry_a/Value.java")));
        assertTrue(Files.exists(output.resolve("entry-b/dto/ru/petrovov/iib_camel/runtime_lite/generated/entry_b/Value.java")));
        var validator = SchemaInputs.load(first).schema().newValidator();
        validator.validate(new StreamSource(output.resolve("entry-a/xml/First.xml").toFile()));
        validator.validate(new StreamSource(output.resolve("entry-a/xml/Second.xml").toFile()));
        Path sample = output.resolve("entry-b/xml/First.xml");
        SchemaInputs.load(second).schema().newValidator().validate(new StreamSource(sample.toFile()));
        String original = Files.readString(sample);
        assertNotEquals(0, run(arguments).exit());
        assertEquals(original, Files.readString(sample));
    }

    private Path simpleSchema() throws Exception {
        return Files.writeString(temporary.resolve("entry.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="Sample" type="xs:string"/>
            </xs:schema>
            """);
    }

    private Result run(Path schema, Path output, String... options) {
        var args = new ArrayList<>(List.of("--xsd", schema.toString(), "--out", output.toString()));
        args.addAll(List.of(options));
        return run(args.toArray(String[]::new));
    }

    private Result run(String... args) {
        var diagnostics = new StringWriter();
        var command = XsdTool.commandLine();
        command.setOut(new PrintWriter(new StringWriter()));
        command.setErr(new PrintWriter(diagnostics));
        return new Result(command.execute(args), diagnostics.toString());
    }

    record Result(int exit, String diagnostics) {
    }
}
