package ru.petrovov.iib_camel.converter.mapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import ru.petrovov.iib_camel.converter.schema.SchemaParser;
import ru.petrovov.iib_camel.converter.schema.SchemaRegistry;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphicalMapParserTest {
    @TempDir Path temp;

    @Test void freshlyGeneratedMapperCompilesWithProcessorAndMapsDataAsSpringBean() throws Exception {
        Path project = copyProject();
        SchemaRegistry schemas = new SchemaParser().parseSchemas(project);
        assertEquals(List.of(), schemas.diagnostics());
        MappingResult result = new GraphicalMapParser().parseMap(project.resolve("CustomerMove.map"), schemas);
        assertEquals(List.of(), result.diagnostics());
        GeneratedMapper mapper = result.mapper();
        assertTrue(mapper.javaSource().contains("@Mapper(componentModel = \"spring\")"));
        List<Path> files = new ArrayList<>();
        Path source = Files.createDirectories(temp.resolve("sources"));
        for (var model : schemas.models()) files.add(writeJava(source, model.className(), model.javaSource()));
        files.add(writeJava(source, mapper.className(), mapper.javaSource()));
        Path classes = Files.createDirectories(temp.resolve("classes"));
        Path generated = Files.createDirectories(temp.resolve("processed"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "25", "-classpath", System.getProperty("java.class.path"),
                            "-processor", "org.mapstruct.ap.MappingProcessor", "-d", classes.toString(), "-s", generated.toString()),
                    null, manager.getJavaFileObjectsFromPaths(files)).call(), diagnostics.getDiagnostics().toString());
        }
        assertTrue(Files.exists(generated.resolve("ru/petrovov/iib_camel/runtime/generated/mapping/CustomerMoveMapperImpl.java")));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
             var context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader);
            context.scan(GeneratedMapper.PACKAGE);
            context.refresh();
            Object bean = context.getBean(mapper.mapperBeanName());
            Class<?> input = loader.loadClass("ru.petrovov.iib_camel.runtime.generated.model.Customer");
            Object customer = input.getConstructor().newInstance();
            input.getMethod("setName", String.class).invoke(customer, "Иван");
            input.getMethod("setBalance", BigDecimal.class).invoke(customer, new BigDecimal("123.45"));
            input.getMethod("setUntouched", String.class).invoke(customer, "must not copy implicitly");
            Object mapped = bean.getClass().getMethod("map", input).invoke(bean, customer);
            assertEquals("Иван", mapped.getClass().getMethod("getDisplayName").invoke(mapped));
            assertEquals(new BigDecimal("123.45"), mapped.getClass().getMethod("getAmount").invoke(mapped));
            assertNull(mapped.getClass().getMethod("getUntouched").invoke(mapped));
            assertNull(bean.getClass().getMethod("map", input).invoke(bean, new Object[]{null}));
        }
    }

    @Test void originalIbmToolkitFixtureRejectsUnsupportedGraphicalTransforms() throws Exception {
        Path original = Path.of(getClass().getResource("/mapping/ibm/TCPIPClient_Mapping.map").toURI());
        MappingResult result = new GraphicalMapParser().parseMap(original, new SchemaRegistry(List.of(), List.of()));
        assertFalse(result.supported());
        assertNull(result.mapper());
        assertTrue(result.diagnostics().getFirst().message().contains("foreach"));
    }

    @Test void rejectsUnknownOperationMissingFieldsMismatchedTypesAndDuplicateTargets() throws Exception {
        Path project = copyProject();
        Path map = project.resolve("CustomerMove.map");
        String original = Files.readString(map);
        SchemaRegistry registry = new SchemaParser().parseSchemas(project);
        for (String invalid : List.of(original.replace("<move>", "<convert>").replace("</move>", "</convert>"),
                original.replace("/Customer/name", "/Customer/missing"),
                original.replace("/Customer/name", "/Customer/balance"),
                original.replace("/CustomerView/amount", "/CustomerView/displayName"),
                original.replace("8.0.5.0", "9.9.9.9"))) {
            Files.writeString(map, invalid);
            MappingResult result = new GraphicalMapParser().parseMap(map, registry);
            assertFalse(result.supported());
            assertNull(result.mapper());
            assertFalse(result.diagnostics().isEmpty());
        }
    }

    @Test void rejectsInventedMappingXmlAndEscapingSchemaReferences() throws Exception {
        Path project = copyProject();
        Path map = project.resolve("CustomerMove.map");
        String original = Files.readString(map);
        Files.writeString(map, original.replace("/Customer.xsd", "../Customer.xsd"));
        assertFalse(new GraphicalMapParser().parseMap(map, new SchemaParser().parseSchemas(project)).supported());
        Files.writeString(map, "<map source=\"Customer\" target=\"CustomerView\"><field source=\"name\" target=\"displayName\"/></map>");
        assertFalse(new GraphicalMapParser().parseMap(map, new SchemaRegistry(List.of(), List.of())).supported());
    }

    private Path copyProject() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        for (String name : List.of("CustomerMove.map", "Customer.xsd")) {
            Files.copy(Path.of(getClass().getResource("/mapping/" + name).toURI()), project.resolve(name));
        }
        return project;
    }

    private static Path writeJava(Path directory, String name, String java) throws Exception {
        Path file = directory.resolve(name + ".java");
        Files.writeString(file, java);
        return file;
    }
}
