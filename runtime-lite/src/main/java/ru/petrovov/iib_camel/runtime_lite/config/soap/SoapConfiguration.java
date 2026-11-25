package ru.petrovov.iib_camel.runtime_lite.config.soap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.petrovov.iib_camel.runtime_lite.config.transport.ListenerProperties;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapService;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadCodec;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SoapProperties.class, ListenerProperties.class})
public class SoapConfiguration {

    @Bean
    @ConditionalOnMissingBean(SoapService.class)
    SoapService soapService(SoapProperties properties, ListenerProperties listeners) {
        return new SoapService(listeners.getMaxBodyBytes(), properties.getMaxDepth(), properties.getMaxElements());
    }

    @Bean
    @ConditionalOnMissingBean(JmsPayloadCodec.class)
    JmsPayloadCodec jmsPayloadCodec(SoapProperties properties, SoapService soap, JmsMessageReader reader) {
        return new JmsPayloadCodec(properties, soap, reader);
    }
}
