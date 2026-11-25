package ru.petrovov.iib_camel.runtime_lite.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.petrovov.iib_camel.runtime_lite.config.xml.XmlConfiguration;
import ru.petrovov.iib_camel.runtime_lite.xml.fixture.ObjectFactory;
import ru.petrovov.iib_camel.runtime_lite.xml.fixture.ObjectFactory.NamedDocument;

import javax.xml.namespace.QName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlServiceTest {

    private static final String SAMPLE_NAMESPACE = "urn:example:sample:v1";
    private static final String SCHEMA = "classpath:xml/service/sample.xsd";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(XmlConfiguration.class);

    @Test
    void readsAndWritesWithOptionalSchema() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(XmlService.class);
            var xml = result.getBean(XmlService.class);
            var payload = new Sample("Пример & value");

            String serialized = xml.writeValidated(payload, SCHEMA);

            assertThat(xml.detectRoot(serialized)).isEqualTo(new QName(SAMPLE_NAMESPACE, "sample"));
            assertThat(xml.readValidated(serialized, SCHEMA, Sample.class)).isEqualTo(payload);
            assertThat(xml.read(xml.validate(serialized, SCHEMA), Sample.class)).isEqualTo(payload);
            assertThat(xml.read(xml.writeValidatedDocument(payload, SCHEMA), Sample.class)).isEqualTo(payload);
            assertThat(xml.validate(serialized).text()).isEqualTo(serialized);
            assertThat(xml.read(xml.write(payload), Sample.class)).isEqualTo(payload);
        });
    }

    @Test
    void optionalValidationRemainsExplicitAndValidatesOutgoingXml() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            String missingValue = "<sample xmlns='urn:example:sample:v1'/>";

            assertThat(xml.read(missingValue, Sample.class).getValue()).isNull();
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

            assertThat(xml.detectRoot(source)).isEqualTo(new QName(SAMPLE_NAMESPACE, "sample"));
            assertThat(xml.readValidated(source, SCHEMA, Sample.class)).isEqualTo(new Sample("Привет"));
            assertThat(xml.detectRoot("<sample xmlns='urn:other'/>"))
                .isNotEqualTo(xml.detectRoot(source));
        });
    }

    @Test
    void roundTripsSchemaDerivedNamedTypeThroughItsObjectFactory() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            var factory = new ObjectFactory();
            var payload = new NamedDocument("Example value");
            var element = factory.createDocument(payload);

            String serialized = xml.write(element);

            assertThat(xml.detectRoot(serialized)).isEqualTo(element.getName());
            assertThat(xml.read(serialized, NamedDocument.class)).isEqualTo(payload);
            String wrongRoot = serialized.replace(element.getName().getNamespaceURI(), "urn:wrong");
            assertThatThrownBy(() -> xml.read(wrongRoot, NamedDocument.class))
                .isInstanceOf(XmlProcessingException.class);
        });
    }

    @Test
    void rejectsDtdMalformedDocumentsAndUnmappedRootsWithoutRevealingContent() {
        context.run(result -> {
            var xml = result.getBean(XmlService.class);
            var secret = "synthetic-secret-value";
            String dtd = "<!DOCTYPE sample [<!ENTITY a '" + secret + "'>]><sample>&a;</sample>";
            String malformed = "<sample><value>" + secret + "</sample>";
            String trailing = "<sample/><other/>";

            for (String input : new String[] {dtd, malformed, trailing}) {
                assertThatThrownBy(() -> xml.validate(input))
                    .isInstanceOf(XmlProcessingException.class)
                    .hasMessageNotContaining(secret);
                assertThatThrownBy(() -> xml.read(input, Sample.class))
                    .isInstanceOf(XmlProcessingException.class)
                    .hasMessageNotContaining(secret);
            }
            assertThatThrownBy(() -> xml.write(secret))
                .isInstanceOf(XmlProcessingException.class)
                .hasMessageNotContaining(secret);
            assertThatThrownBy(() -> xml.read("<other><value>" + secret + "</value></other>", Sample.class))
                .isInstanceOf(XmlProcessingException.class);
        });
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlRootElement(name = "sample", namespace = SAMPLE_NAMESPACE)
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Sample {

        @XmlElement(namespace = SAMPLE_NAMESPACE)
        private String value;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @XmlRootElement(name = "other")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Other {

        private String value;
    }
}
