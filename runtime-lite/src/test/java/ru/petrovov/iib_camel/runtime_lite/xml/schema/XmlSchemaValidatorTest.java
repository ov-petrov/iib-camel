package ru.petrovov.iib_camel.runtime_lite.xml.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlSchemaValidatorTest {

    private static final String SCHEMA = "classpath:/xml-schema/request.xsd";
    private final XmlSchemaRegistry registry = new XmlSchemaRegistry(new DefaultResourceLoader());
    private final XmlSchemaValidator validator = new XmlSchemaValidator(registry);

    @Test
    void validatesWithIncludedSchemaAndCachesCompiledSchema() {
        assertThatCode(() -> validator.validate("<request xmlns=\"urn:example:request\"><value>1</value></request>", SCHEMA))
            .doesNotThrowAnyException();
        assertThat(registry.get(SCHEMA)).isSameAs(registry.get("classpath:xml-schema/request.xsd"));
        assertThatThrownBy(() -> validator.validate("<request xmlns=\"urn:example:request\"><value>secret</value></request>", SCHEMA))
            .isInstanceOf(XmlSchemaException.class)
            .hasMessageContaining("line 1")
            .hasMessageNotContaining("secret");
    }

    @Test
    void validatesWithImportedSchema() {
        assertThatCode(() -> validator.validate("<request xmlns=\"urn:example:import\">4</request>",
            "classpath:/xml-schema/import.xsd")).doesNotThrowAnyException();
    }

    @Test
    void rejectsDtdAndExternalEntitiesInMessage() {
        var xml = "<!DOCTYPE request [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>"
            + "<request xmlns='urn:example:request'><value>&secret;</value></request>";
        assertThatThrownBy(() -> validator.validate(xml, SCHEMA)).isInstanceOf(XmlSchemaException.class);
    }

    @Test
    void rejectsExternalOrOutOfDirectorySchemaReferencesAndDtd() {
        for (String file : new String[] {"escape.xsd", "external.xsd", "dtd.xsd", "include-dtd.xsd"}) {
            assertThatThrownBy(() -> registry.get("classpath:/xml-schema/" + file))
                .as(file)
                .isInstanceOf(XmlSchemaException.class);
        }
        assertThatThrownBy(() -> registry.get("file:///tmp/schema.xsd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.get("classpath:/xml-schema/../outside.xsd"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.get("classpath:/xml-schema/missing.xsd"))
            .isInstanceOf(XmlSchemaException.class);
    }

    @Test
    void loadsSchemaDependenciesFromJar(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("schemas.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            addJarResource(output, "xml-schema/request.xsd");
            addJarResource(output, "xml-schema/shared/types.xsd");
        }
        try (var loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
            var jarValidator = new XmlSchemaValidator(new XmlSchemaRegistry(new DefaultResourceLoader(loader)));
            assertThatCode(() -> jarValidator.validate(
                "<request xmlns='urn:example:request'><value>1</value></request>", SCHEMA))
                .doesNotThrowAnyException();
        }
    }

    private static void addJarResource(JarOutputStream output, String name) throws Exception {
        output.putNextEntry(new JarEntry(name));
        try (var resource = XmlSchemaValidatorTest.class.getClassLoader().getResourceAsStream(name)) {
            resource.transferTo(output);
        }
        output.closeEntry();
    }
}
