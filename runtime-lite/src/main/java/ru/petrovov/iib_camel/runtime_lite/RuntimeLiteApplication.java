package ru.petrovov.iib_camel.runtime_lite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RuntimeLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuntimeLiteApplication.class, args);
    }
}
