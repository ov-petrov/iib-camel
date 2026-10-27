package ru.petrovov.iib_camel.runtime_lite.xml;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

@Configuration(proxyBeanMethods = false)
public class XmlConfiguration {

    @Bean
    @ConditionalOnMissingBean(XmlMapper.class)
    XmlMapper xmlMapper() {
        var inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputFactory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are forbidden");
        });
        return XmlMapper.builder(XmlFactory.builder().xmlInputFactory(inputFactory).build()).build();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(JsonMapper.class)
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }
}
