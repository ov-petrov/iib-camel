package ru.petrovov.iib_camel.runtime_lite.config.json;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(JsonMapper.class)
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }
}
