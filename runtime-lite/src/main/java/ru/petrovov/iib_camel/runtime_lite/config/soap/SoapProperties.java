package ru.petrovov.iib_camel.runtime_lite.config.soap;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "runtime-lite.soap", ignoreUnknownFields = false)
public class SoapProperties {

    @NotNull
    private Set<@NotBlank String> plainJmsDestinations = new LinkedHashSet<>();

    @Min(3)
    private int maxDepth = 128;

    @Min(3)
    private int maxElements = 100000;
}
