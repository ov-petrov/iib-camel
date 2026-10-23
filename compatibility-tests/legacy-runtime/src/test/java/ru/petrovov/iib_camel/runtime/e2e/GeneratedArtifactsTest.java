package ru.petrovov.iib_camel.runtime.e2e;

import ru.petrovov.iib_camel.converter.cli.ConvertCommand;
import ru.petrovov.iib_camel.converter.esql.EsqlToJavaGenerator;
import ru.petrovov.iib_camel.runtime.config.CamelMessagingConfig;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;

import javax.tools.ToolProvider;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedArtifactsTest {
    private static final List<String> DATASETS = List.of("corr-01-simple-adapter", "corr-02-subflows");
    private static final String PREFIX = "GENERATED.";

    @TempDir
    static Path temp;
    @Autowired
    ConnectionFactory connectionFactory;

    private DefaultCamelContext camel;
    private URLClassLoader generatedClasses;
    private JmsTemplate receiver;
    private List<String> queues;

    record DatasetCase(String directory, String name, Map<String, Object> input,
                       Map<String, Object> expect) {
        @Override public String toString() { return directory + ": " + name; }
    }

    @BeforeAll
    void generateCompileAndStart() throws Exception {
        Path input = temp.resolve("input");
        Path output = temp.resolve("output");
        Path classes = temp.resolve("classes");
        Files.createDirectories(input);
        Files.createDirectories(classes);
        for (String dir : List.of("corr-01-simple-adapter", "corr-02-subflows", "corr-03-esql-mapping")) {
            Path fixture = Path.of(Objects.requireNonNull(getClass().getResource("/corpus/" + dir)).toURI());
            try (Stream<Path> files = Files.list(fixture)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (file.toString().matches(".*\\.(msgflow|subflow|esql)$")) {
                        Files.copy(file, input.resolve(file.getFileName()));
                    }
                }
            }
        }
        Files.writeString(input.resolve("Body_Compute.esql"), """
                CREATE COMPUTE MODULE Body_Compute
                  CREATE FUNCTION Main() RETURNS BOOLEAN
                  BEGIN
                    SET OutputRoot = InputRoot;
                    SET OutputRoot.XMLNSC.Order.Status = 'INTERMEDIATE';
                    SET OutputRoot.MQMD.Scratch = 'DROP';
                    SET OutputRoot = InputRoot;
                    SET OutputRoot.XMLNSC.Order.Status = 'READY';
                    SET OutputRoot.MQMD.CommentField = 'BODY-UPDATED';
                    RETURN TRUE;
                  END;
                END MODULE;
                """);
        assertEquals(0, new CommandLine(new ConvertCommand()).execute(
                "--input", input.toString(), "--output", output.toString(), "--no-strict"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Generated-source checks require a JDK");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(output)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertTrue(sources.size() >= 4);
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var diagnostics = new StringWriter();
            boolean compiled = compiler.getTask(diagnostics, manager, null,
                    List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromPaths(sources)).call();
            assertTrue(compiled, diagnostics.toString());
        }
        generatedClasses = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // Saved generated classes on the parent classpath must not mask fresh output.
                if (name.startsWith(EsqlToJavaGenerator.GENERATED_PACKAGE + ".")) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        };
        camel = new DefaultCamelContext();
        camel.addComponent("jms", new CamelMessagingConfig().jmsComponent(connectionFactory));
        try (Stream<Path> files = Files.list(output.resolve("beans"))) {
            for (Path source : files.toList()) {
                String className = source.getFileName().toString().replace(".java", "");
                Class<?> type = generatedClasses.loadClass(EsqlToJavaGenerator.GENERATED_PACKAGE + "." + className);
                camel.getRegistry().bind(type.getAnnotation(Component.class).value(), type.getConstructor().newInstance());
            }
        }
        try (Stream<Path> files = Files.list(output)) {
            for (Path route : files.filter(p -> p.toString().endsWith("-route.xml")).toList()) {
                String xml = Files.readString(route)
                        .replace("jms:", "jms:" + PREFIX);
                PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString(route.getFileName().toString(), xml));
            }
        }
        receiver = new JmsTemplate(connectionFactory);
        queues = DATASETS.stream().flatMap(dir -> ((List<String>) dataset(dir).get("queues")).stream()).distinct().toList();
        camel.start();
    }

    @AfterAll
    void stop() throws Exception {
        if (camel != null) camel.close();
        if (generatedClasses != null) generatedClasses.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void freshArtifactsPreserveRoutingBodyAndQos(DatasetCase sample) throws Exception {
        receiver.setReceiveTimeout(100);
        for (String queue : queues) while (receiver.receive(PREFIX + queue) != null) { }
        String body = resource("/e2e/" + sample.directory() + "/" + sample.input().get("message")).strip();
        var sender = new JmsTemplate(connectionFactory);
        sender.setExplicitQosEnabled(true);
        Map<String, Object> qos = (Map<String, Object>) sample.input().get("mqmd");
        if (qos != null && qos.get("priority") != null) sender.setPriority((Integer) qos.get("priority"));
        sender.convertAndSend(PREFIX + sample.input().get("queue"), body);
        String expectedQueue = (String) sample.expect().get("queue");
        receiver.setReceiveTimeout(10_000);
        Message message = receiver.receive(PREFIX + expectedQueue);
        assertNotNull(message, sample.toString());
        assertInstanceOf(TextMessage.class, message);
        assertEquals(body, ((TextMessage) message).getText());
        for (String property : (List<String>) sample.expect().getOrDefault("absentProperties", List.of())) {
            assertFalse(message.propertyExists(property), "Input snapshot must remove " + property);
        }
        Map<String, Object> expectedQos = (Map<String, Object>) sample.expect().get("mqmd");
        if (expectedQos != null) {
            if (expectedQos.get("priority") != null) assertEquals(expectedQos.get("priority"), message.getJMSPriority());
            if (expectedQos.get("applicationIdData") != null) {
                assertEquals(expectedQos.get("applicationIdData"), message.getStringProperty("ApplicationIdData"));
            }
            if (expectedQos.get("commentField") != null) {
                assertEquals(expectedQos.get("commentField"), message.getStringProperty("CommentField"));
            }
        }
        receiver.setReceiveTimeout(150);
        for (String queue : queues) {
            if (!queue.equals(sample.input().get("queue"))) {
                assertNull(receiver.receive(PREFIX + queue), "Unexpected or duplicate delivery to " + queue);
            }
        }
    }

    @Test
    void partialBeanFailsBeforeChangingBodyOrHeaders() throws Exception {
        Class<?> type = generatedClasses.loadClass(EsqlToJavaGenerator.GENERATED_PACKAGE + ".AdjustCompute1");
        Object bean = type.getConstructor().newInstance();
        var exchange = new DefaultExchange(camel);
        String body = "<Order><Status>HOLD</Status></Order>";
        exchange.getIn().setBody(body);
        exchange.getIn().setHeader("keep", "value");
        var headers = Map.copyOf(exchange.getIn().getHeaders());
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> type.getMethod("compute", org.apache.camel.Exchange.class).invoke(bean, exchange));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        assertEquals(body, exchange.getIn().getBody());
        assertEquals(headers, exchange.getIn().getHeaders());
    }

    @Test
    void generatedXmlSetterAndRepeatedCopyPreserveInputSemantics() throws Exception {
        Class<?> type = generatedClasses.loadClass(EsqlToJavaGenerator.GENERATED_PACKAGE + ".BodyCompute");
        var bean = (ru.petrovov.iib_camel.runtime.compute.AbstractComputeBean) type.getConstructor().newInstance();
        var exchange = new DefaultExchange(camel);
        exchange.getIn().setBody("<Order><Status>NEW</Status><Description>A B</Description></Order>");
        exchange.getIn().setHeader("keep", "value");
        bean.compute(exchange);
        assertEquals("<Order><Status>READY</Status><Description>A B</Description></Order>", exchange.getIn().getBody());
        assertEquals("value", exchange.getIn().getHeader("keep"));
        assertNull(exchange.getIn().getHeader("Scratch"));
        assertEquals("BODY-UPDATED", exchange.getIn().getHeader("CommentField"));
    }

    static Stream<DatasetCase> cases() {
        var result = new ArrayList<DatasetCase>();
        for (String dir : DATASETS) {
            List<Map<String, Object>> cases = (List<Map<String, Object>>) dataset(dir).get("cases");
            for (var sample : cases) result.add(new DatasetCase(dir, (String) sample.get("name"),
                    (Map<String, Object>) sample.get("input"), (Map<String, Object>) sample.get("expect")));
        }
        return result.stream();
    }

    static Map<String, Object> dataset(String dir) {
        try (InputStream in = Objects.requireNonNull(GeneratedArtifactsTest.class.getResourceAsStream("/e2e/" + dir + "/dataset.yml"))) {
            return new Yaml().load(in);
        } catch (Exception e) { throw new IllegalStateException(dir, e); }
    }

    static String resource(String name) throws Exception {
        try (InputStream in = Objects.requireNonNull(GeneratedArtifactsTest.class.getResourceAsStream(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
