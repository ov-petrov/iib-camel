package ru.petrovov.iib_camel.runtime.module;

public record IntegrationModule(String id, String routesPattern) {
    public IntegrationModule {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("Invalid module id");
        if (routesPattern == null || !routesPattern.startsWith("classpath*:modules/" + id + "/routes/"))
            throw new IllegalArgumentException("Module routes must use their own resource directory");
    }
}
