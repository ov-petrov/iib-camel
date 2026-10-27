package ru.petrovov.iib_camel.runtime_lite.transport.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite.kafka")
public class KafkaProducerProperties {

    @NotEmpty
    private List<@NotBlank String> bootstrapServers = List.of("localhost:9092");

    @NotNull
    private Duration sendTimeout = Duration.ofSeconds(10);

    private Map<String, Object> properties = new LinkedHashMap<>();
}
