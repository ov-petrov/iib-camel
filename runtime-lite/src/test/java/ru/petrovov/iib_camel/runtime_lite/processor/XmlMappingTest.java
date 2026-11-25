package ru.petrovov.iib_camel.runtime_lite.processor;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.core.io.DefaultResourceLoader;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaRegistry;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaValidator;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XmlMappingTest {

    @Test
    void processorUsesGeneratedMapperWithLombokModelsAndJaxb() {
        var registry = new XmlSchemaRegistry(new DefaultResourceLoader());
        var xml = new XmlService(new XmlSchemaValidator(registry));
        var mapper = Mappers.getMapper(DocumentMapper.class);
        MessageProcessor processor = message -> {
            var source = xml.read(message.body(), SourceDocument.class);
            return OutboundMessage.of(Transport.JMS, "output", xml.write(mapper.convert(source)));
        };
        var inbound = new InboundMessage("synthetic-1",
            "<document><label>Example &amp; value</label></document>", Map.of());

        var result = xml.read(processor.process(inbound).body(), ResultDocument.class);

        assertThat(result.getValue()).isEqualTo("Example & value");
    }

    @Data
    @XmlRootElement(name = "document")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class SourceDocument {

        private String label;
    }

    @Data
    @XmlRootElement(name = "result")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class ResultDocument {

        private String value;
    }

    @Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
    public interface DocumentMapper {

        @Mapping(target = "value", source = "label")
        ResultDocument convert(SourceDocument source);
    }
}
