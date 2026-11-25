package ru.petrovov.iib_camel.runtime_lite.config.xml;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaRegistry;
import ru.petrovov.iib_camel.runtime_lite.xml.schema.XmlSchemaValidator;

@Configuration(proxyBeanMethods = false)
public class XmlConfiguration {

    @Bean
    @ConditionalOnMissingBean(XmlSchemaRegistry.class)
    XmlSchemaRegistry xmlSchemaRegistry(ResourceLoader resourceLoader) {
        return new XmlSchemaRegistry(resourceLoader);
    }

    @Bean
    @ConditionalOnMissingBean(XmlSchemaValidator.class)
    XmlSchemaValidator xmlSchemaValidator(XmlSchemaRegistry registry) {
        return new XmlSchemaValidator(registry);
    }

    @Bean
    @ConditionalOnMissingBean(XmlService.class)
    XmlService xmlService(XmlSchemaValidator validator) {
        return new XmlService(validator);
    }
}
