package ru.petrovov.iib_camel.runtime.modules.example;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("iib.modules.example")
public class ExampleModuleProperties {
    private boolean enabled;
    private String inputUri = "direct:example-input";
    private String sourceId = "example-input";
    private String outputTransport = "artemis";
    private String outputDestination = "EXAMPLE.OUT";

    @PostConstruct
    void validateWhenEnabled() {
        if (!enabled) {
            return;
        }
        requireText(inputUri, "input-uri");
        requireText(sourceId, "source-id");
        requireText(outputTransport, "output-transport");
        requireText(outputDestination, "output-destination");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Example module " + name + " must not be blank when enabled");
        }
    }
}
