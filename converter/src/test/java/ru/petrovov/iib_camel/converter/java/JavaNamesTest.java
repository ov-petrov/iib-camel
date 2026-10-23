package ru.petrovov.iib_camel.converter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.petrovov.iib_camel.converter.esql.EsqlToJavaGenerator;
import ru.petrovov.iib_camel.converter.esql.EsqlTranslator;
import ru.petrovov.iib_camel.converter.report.UnitStatus;

import javax.tools.ToolProvider;
import java.beans.Introspector;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaNamesTest {

    @TempDir
    Path temp;

    @Test
    void generatedModelCompilesAndExposesJavaBeanProperties() throws Exception {
        var statements = List.of(
                statement("Order.class"),
                statement("Order.eMail"),
                statement("Order.URL"),
                statement("Order.Имя"));
        var model = new EsqlToJavaGenerator().generateModels(statements).getFirst();
        Path sourceRoot = Files.createDirectories(temp.resolve("sources"));
        Path source = sourceRoot.resolve(model.className() + ".java");
        Files.writeString(source, model.java());
        Path classes = Files.createDirectories(temp.resolve("classes"));

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "тест должен выполняться на JDK, содержащем JavaCompiler");
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null,
                    List.of("--release", "25", "-proc:none", "-d", classes.toString()),
                    null, manager.getJavaFileObjects(source)).call(),
                    "сгенерированный Java-код должен компилироваться");
        }

        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            Class<?> modelClass = loader.loadClass(EsqlToJavaGenerator.GENERATED_MODEL_PACKAGE + ".Order");
            Object instance = modelClass.getConstructor().newInstance();
            Map<String, java.beans.PropertyDescriptor> properties = Arrays.stream(
                            Introspector.getBeanInfo(modelClass).getPropertyDescriptors())
                    .collect(Collectors.toMap(java.beans.PropertyDescriptor::getName, Function.identity()));

            Map<String, String> values = Map.of(
                    "classValue", "keyword",
                    "eMail", "mail",
                    "URL", "acronym",
                    "имя", "unicode");
            for (var entry : values.entrySet()) {
                var property = properties.get(entry.getKey());
                assertNotNull(property, "JavaBeans должен распознать свойство " + entry.getKey());
                assertNotNull(property.getReadMethod(), "getter для " + entry.getKey());
                assertNotNull(property.getWriteMethod(), "setter для " + entry.getKey());
                property.getWriteMethod().invoke(instance, entry.getValue());
                assertEquals(entry.getValue(), property.getReadMethod().invoke(instance));
            }
        }
    }

    @Test
    void normalizesKeywordsLeadingDigitsAndUnicodeIdentifiers() {
        assertEquals("classValue", JavaNames.propertyName("class"));
        assertEquals("value7Code", JavaNames.propertyName("7-code"));
        assertEquals("имя", JavaNames.propertyName("Имя"));
        assertEquals("URL", JavaNames.propertyName("URL"));
    }

    private static EsqlTranslator.StatementTranslation statement(String path) {
        return new EsqlTranslator.StatementTranslation("SET " + path + " = 'value';",
                "setBodyField(exchange, \"" + path + "\", \"value\");", UnitStatus.CONVERTED, "");
    }
}
