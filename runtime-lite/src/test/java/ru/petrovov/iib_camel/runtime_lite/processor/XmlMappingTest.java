package ru.petrovov.iib_camel.runtime_lite.processor;

import lombok.Data;
import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;
import tools.jackson.dataformat.xml.XmlMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XmlMappingTest {

    @Test
    void processorUsesGeneratedMapperWithLombokModelsAndJacksonXml() {
        var xml = XmlMapper.builder().build();
        var mapper = Mappers.getMapper(DocumentMapper.class);
        MessageProcessor processor = message -> {
            var source = xml.readValue(message.body(), SourceDocument.class);
            return OutboundMessage.of(Transport.JMS, "output", xml.writeValueAsString(mapper.convert(source)));
        };
        var inbound = new InboundMessage("synthetic-1",
            "<document><label>Example &amp; value</label></document>", Map.of());

        var result = xml.readValue(processor.process(inbound).body(), ResultDocument.class);

        assertThat(result.getValue()).isEqualTo("Example & value");
    }

    @Data
    public static class SourceDocument {

        private String label;
    }

    @Data
    public static class ResultDocument {

        private String value;
    }

    @Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
    public interface DocumentMapper {

        @Mapping(target = "value", source = "label")
        ResultDocument convert(SourceDocument source);
    }
}
