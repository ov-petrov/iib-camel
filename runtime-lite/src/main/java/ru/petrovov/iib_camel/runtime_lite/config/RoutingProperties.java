package ru.petrovov.iib_camel.runtime_lite.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Validated
@ConfigurationProperties("runtime-lite")
public class RoutingProperties {

    @NotNull
    private Map<@NotBlank String, @Valid Route> routes = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Route {

        @NotBlank
        private String inputQueue;

        @NotBlank
        private String processor;

        @Min(1)
        private int concurrency = 1;
    }
}
