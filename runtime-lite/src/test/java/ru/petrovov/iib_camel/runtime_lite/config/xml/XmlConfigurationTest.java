package ru.petrovov.iib_camel.runtime_lite.config.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;
import ru.petrovov.iib_camel.runtime_lite.config.json.JsonConfiguration;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlProcessingException;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaRegistry;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(XmlConfiguration.class, JsonConfiguration.class);

    @Test
    void roundTripsXmlAndKeepsJsonMapperPrimary() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(XmlService.class).hasSingleBean(JsonMapper.class);
            assertThat(result.getBean(ObjectMapper.class)).isInstanceOf(JsonMapper.class);
            var xml = result.getBean(XmlService.class);
            var payload = new Payload("Привет <&>");

            assertThat(xml.read(xml.write(payload), Payload.class)).isEqualTo(payload);
        });
    }

    @Test
    void acceptsApplicationXmlServiceAndJsonMapper() {
        var registry = new XmlSchemaRegistry(new DefaultResourceLoader());
        var xmlService = new XmlService(new XmlSchemaValidator(registry));
        var jsonMapper = JsonMapper.builder().build();
        context.withBean(XmlService.class, () -> xmlService)
            .withBean(JsonMapper.class, () -> jsonMapper)
            .run(result -> {
                assertThat(result).hasNotFailed().hasSingleBean(XmlService.class).hasSingleBean(JsonMapper.class);
                assertThat(result.getBean(XmlService.class)).isSameAs(xmlService);
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
            var xml = result.getBean(XmlService.class);
            assertThatThrownBy(() -> xml.read(external, Payload.class)).isInstanceOf(XmlProcessingException.class);
            assertThatThrownBy(() -> xml.read(internal, Payload.class)).isInstanceOf(XmlProcessingException.class);
        });
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlRootElement(name = "Payload")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Payload {

        private String value;
    }
}
