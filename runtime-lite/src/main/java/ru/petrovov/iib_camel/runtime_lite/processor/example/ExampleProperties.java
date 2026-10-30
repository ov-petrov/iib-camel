package ru.petrovov.iib_camel.runtime_lite.processor.example;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@Profile("local")
@ConfigurationProperties("runtime-lite.example")
class ExampleProperties {

    @NotBlank
    private String mainTopic;

    @NotBlank
    private String mailTopic;

    @NotBlank
    private String errorTopic;
}
