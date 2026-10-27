package ru.petrovov.iib_camel.runtime_lite.transport.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.transports")
public class TransportsProperties {

    @NotNull
    private Map<@NotBlank String, @NotNull @Valid NamedMqProperties> mq = new LinkedHashMap<>();

    @NotNull
    private Map<@NotBlank String, @NotNull @Valid NamedKafkaProperties> kafka = new LinkedHashMap<>();

    public static class NamedMqProperties extends MqProperties {

        public NamedMqProperties() {
            setHost(null);
            setQueueManager(null);
            setChannel(null);
        }
    }

    public static class NamedKafkaProperties extends KafkaProducerProperties {

        public NamedKafkaProperties() {
            setBootstrapServers(List.of());
        }
    }
}
