package ru.petrovov.iib_camel.runtime_lite.xml;

import com.fasterxml.jackson.annotation.JsonRootName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.petrovov.iib_camel.runtime_lite.config.xml.XmlConfiguration;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlServiceTest {

    private static final String SCHEMA = "classpath:xml/service/sample.xsd";
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(XmlConfiguration.class);

    @Test
    void readsAndWritesWithOptionalSchemaAndConfiguredWriter() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(XmlService.class);
            var xml = result.getBean(XmlService.class);
            var payload = new Sample("Пример & value");

            String serialized = xml.writeValidated(payload, xml.writerFor(Sample.class).withDefaultPrettyPrinter(), SCHEMA);

            assertThat(xml.detectRoot(serialized)).isEqualTo(new QName("urn:example:sample:v1", "sample"));
            assertThat(xml.readValidated(serialized, SCHEMA, Sample.class)).isEqualTo(payload);
            assertThat(xml.read(xml.write(payload), Sample.class)).isEqualTo(payload);
        });
    }

    @Test
    void optionalValidationRemainsExplicitAndValidatesOutgoingXml() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            String missingValue = "<sample xmlns='urn:example:sample:v1'/>";

            assertThat(xml.read(missingValue, Sample.class).value()).isNull();
            assertThatThrownBy(() -> xml.readValidated(missingValue, SCHEMA, Sample.class))
                .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> xml.readValidated(missingValue, "classpath:missing.xsd", Sample.class))
                .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> xml.writeValidated(new Other("x"), SCHEMA))
                .isInstanceOf(RuntimeException.class);
        });
    }

    @Test
    void detectsQualifiedRootAndHonorsXmlEncoding() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            var source = "<?xml version='1.0' encoding='UTF-16'?><s:sample xmlns:s='urn:example:sample:v1'>"
                + "<s:value>Привет</s:value></s:sample>";

            assertThat(xml.detectRoot(source)).isEqualTo(new QName("urn:example:sample:v1", "sample"));
            assertThat(xml.readValidated(source, SCHEMA, Sample.class)).isEqualTo(new Sample("Привет"));
            assertThat(xml.detectRoot("<sample xmlns='urn:other'/>"))
                .isNotEqualTo(xml.detectRoot(source));
        });
    }

    @Test
    void rejectsDtdMalformedDocumentsAndNonXmlWriterWithoutRevealingContent() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            var secret = "synthetic-secret-value";
            String dtd = "<!DOCTYPE sample [<!ENTITY a '" + secret + "'>]><sample>&a;</sample>";
            String malformed = "<sample><value>" + secret + "</sample>";
            String trailing = "<sample/><other/>";

            for (String input : new String[] {dtd, malformed, trailing}) {
                assertThatThrownBy(() -> xml.read(input, Sample.class))
                    .isInstanceOf(XmlProcessingException.class)
                    .hasMessageNotContaining(secret);
            }
            assertThatThrownBy(() -> xml.write(new Other("value"), result.getBean(tools.jackson.databind.json.JsonMapper.class)
                .writerFor(Other.class)))
                .isInstanceOf(XmlProcessingException.class);
        });
    }

    @JsonRootName(value = "sample", namespace = "urn:example:sample:v1")
    record Sample(@JacksonXmlProperty(localName = "value", namespace = "urn:example:sample:v1") String value) {
    }

    record Other(String value) {
    }
}
