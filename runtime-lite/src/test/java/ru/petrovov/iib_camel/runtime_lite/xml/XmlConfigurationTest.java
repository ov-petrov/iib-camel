package ru.petrovov.iib_camel.runtime_lite.xml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.xml.XmlMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(XmlConfiguration.class);

    @Test
    void roundTripsXmlAndKeepsJsonMapperPrimary() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(XmlMapper.class).hasSingleBean(JsonMapper.class);
            assertThat(result.getBean(ObjectMapper.class)).isInstanceOf(JsonMapper.class);
            var mapper = result.getBean(XmlMapper.class);
            var payload = new Payload("Привет <&>");

            assertThat(mapper.readValue(mapper.writeValueAsBytes(payload), Payload.class)).isEqualTo(payload);
        });
    }

    @Test
    void acceptsApplicationXmlAndJsonMappers() {
        var xmlMapper = new XmlConfiguration().xmlMapper();
        var jsonMapper = JsonMapper.builder().build();
        context.withBean(XmlMapper.class, () -> xmlMapper)
            .withBean(JsonMapper.class, () -> jsonMapper)
            .run(result -> {
                assertThat(result).hasNotFailed().hasSingleBean(XmlMapper.class).hasSingleBean(JsonMapper.class);
                assertThat(result.getBean(XmlMapper.class)).isSameAs(xmlMapper);
                assertThat(result.getBean(JsonMapper.class)).isSameAs(jsonMapper);
            });
    }

    @Test
    void rejectsExternalAndInternalEntityExpansion(@TempDir Path directory) throws Exception {
        var file = directory.resolve("synthetic.txt");
        Files.writeString(file, "must-not-be-expanded");
        var external = "<!DOCTYPE Payload [<!ENTITY injected SYSTEM '" + file.toUri()
            + "'>]><Payload><value>&injected;</value></Payload>";
        var internal = "<!DOCTYPE Payload [<!ENTITY injected 'expanded'>]>"
            + "<Payload><value>&injected;</value></Payload>";

        context.run(result -> {
            var mapper = result.getBean(XmlMapper.class);
            assertThatThrownBy(() -> mapper.readValue(external, Payload.class)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> mapper.readValue(internal, Payload.class)).isInstanceOf(RuntimeException.class);
        });
    }

    public record Payload(String value) {
    }
}
