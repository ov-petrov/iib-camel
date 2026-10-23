package ru.petrovov.iib_camel.runtime.xml;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

@AutoConfiguration
public class RuntimeXmlAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(XmlMapper.class)
    XmlMapper xmlMapper() {
        var factory = XmlFactory.builder().xmlInputFactory(new SecureXmlInputFactory()).build();
        return XmlMapper.builder(factory).build();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(JsonMapper.class)
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }
}
