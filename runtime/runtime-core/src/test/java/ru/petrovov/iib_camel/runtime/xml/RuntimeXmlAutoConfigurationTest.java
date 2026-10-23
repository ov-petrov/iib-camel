package ru.petrovov.iib_camel.runtime.xml;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.xml.XmlMapper;
import static org.assertj.core.api.Assertions.*;

class RuntimeXmlAutoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuntimeXmlAutoConfiguration.class));

    @Test void configuresIndependentJsonAndXmlMappersAndRoundTripsEscapedUnicode() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(XmlMapper.class).hasSingleBean(JsonMapper.class);
            assertThat(result.getBean(ObjectMapper.class)).isInstanceOf(JsonMapper.class);
            XmlMapper mapper = result.getBean(XmlMapper.class);
            var original = new Payload("A<&> \" Привет", 7);
            String xml = mapper.writeValueAsString(original);
            assertThat(xml).contains("&lt;", "&amp;");
            assertThat(mapper.readValue(xml, Payload.class)).isEqualTo(original);
        });
    }

    @Test void rejectsExternalAndInternalDtdDeclarations(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("synthetic-secret.txt");
        Files.writeString(secret, "must-not-be-read");
        String external = "<!DOCTYPE Payload [<!ENTITY injected SYSTEM '" + secret.toUri() + "'>]>"
                + "<Payload><text>&injected;</text><count>1</count></Payload>";
        String internal = "<!DOCTYPE Payload [<!ENTITY injected 'forbidden'>]>"
                + "<Payload><text>&injected;</text><count>1</count></Payload>";
        context.run(result -> {
            XmlMapper mapper = result.getBean(XmlMapper.class);
            for (String xml : new String[]{external, internal, "<!DOCTYPE Payload><Payload><text>text</text><count>1</count></Payload>"}) {
                assertThatThrownBy(() -> mapper.readValue(xml, Payload.class)).hasMessageContaining("DTD declarations are forbidden");
            }
        });
    }

    public record Payload(String text, int count) { }
}
