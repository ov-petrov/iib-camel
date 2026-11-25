package ru.petrovov.iib_camel.runtime_lite.xsd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XsdToolJarIT {

    @TempDir
    Path temporary;

    @Test
    void standaloneJarProcessesSchemaWithJaxbBindings() throws Exception {
        Path jar = Files.copy(Path.of(System.getProperty("xsd-tool.jar")), temporary.resolve("xsd-tool.jar"));
        Path input = Files.createDirectory(temporary.resolve("input"));
        Files.writeString(input.resolve("sample.xsd"), """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       xmlns:jaxb="https://jakarta.ee/xml/ns/jaxb" jaxb:version="3.0">
              <xs:complexType name="SampleType">
                <xs:annotation>
                  <xs:appinfo><jaxb:class name="CustomSample"/></xs:appinfo>
                </xs:annotation>
                <xs:sequence>
                  <xs:element name="value" type="xs:string"/>
                </xs:sequence>
              </xs:complexType>
              <xs:element name="Sample" type="SampleType"/>
            </xs:schema>
            """);
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = Files.exists(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java");
        Path log = temporary.resolve("launch.log");
        var process = new ProcessBuilder(java.toString(), "-jar", jar.toString())
            .directory(Files.createDirectory(temporary.resolve("work")).toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "Timed out running " + jar);
            assertEquals(0, process.exitValue(), Files.readString(log));
            Path output = temporary.resolve("output/sample");
            Path dtoPackage = output.resolve("dto/ru/petrovov/iib_camel/runtime_lite/generated/sample");
            assertTrue(Files.isRegularFile(dtoPackage.resolve("CustomSample.java")));
            assertTrue(Files.isRegularFile(output.resolve("xml/Sample.xml")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
