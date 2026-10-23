package ru.petrovov.iib_camel.converter.mapping;

import java.nio.file.Path;
import ru.petrovov.iib_camel.converter.java.JavaNames;

public record GeneratedMapper(Path source, String mapName, String className, String beanName,
                              String inputClass, String outputClass, String javaSource, String processorSource) {
    public static final String PACKAGE = "ru.petrovov.iib_camel.runtime.generated.mapping";

    public GeneratedMapper(Path source, String mapName, String className, String beanName,
                           String inputClass, String outputClass, String javaSource) {
        this(source, mapName, className, beanName, inputClass, outputClass, javaSource, null);
    }

    public String processorClassName() { return className.substring(0, className.length() - "Mapper".length()) + "Processor"; }
    public String processorJava() { return processorSource; }
    public String mapperBeanName() { return JavaNames.propertyName(className) + "Impl"; }
}
