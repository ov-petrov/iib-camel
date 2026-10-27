package ru.petrovov.iib_camel.runtime_lite.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite")
public class RoutingProperties {

    @NotNull
    private Map<@NotBlank String, @Valid Route> routes = new LinkedHashMap<>();

    @NotNull
    private Map<@NotBlank String, @Valid Destination> destinations = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Route {

        @NotBlank
        private String inputQueue;

        @NotBlank
        private String processor;

        @NotBlank
        private String output;

        @Min(1)
        private int concurrency = 1;
    }

    @Getter
    @Setter
    public static class Destination {

        @NotNull
        private Transport transport;

        @NotBlank
        private String name;
    }
}
