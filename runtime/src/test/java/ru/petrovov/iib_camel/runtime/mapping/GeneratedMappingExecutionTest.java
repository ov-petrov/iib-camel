package ru.petrovov.iib_camel.runtime.mapping;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import ru.petrovov.iib_camel.converter.camel.RouteGenerator;
import ru.petrovov.iib_camel.converter.mapping.GeneratedMapper;
import ru.petrovov.iib_camel.converter.mapping.GraphicalMapParser;
import ru.petrovov.iib_camel.converter.msgflow.FlowConnection;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.schema.SchemaModel;
import ru.petrovov.iib_camel.converter.schema.SchemaParser;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedMappingExecutionTest {
    @TempDir Path temp;

    @Test void freshSchemaMapperSpringProcessorAndGeneratedCamelXmlRouteMapWireXml() throws Exception {
        try (Generated generated = compile(); var camel = new DefaultCamelContext()) {
            Object processor = generated.spring().getBean(generated.mapper().beanName());
            camel.getRegistry().bind(generated.mapper().beanName(), processor);
            List<String> deliveries = new ArrayList<>();
            camel.addRoutes(new RouteBuilder() {
                @Override public void configure() {
                    from("direct:OUT").process(exchange -> deliveries.add(exchange.getIn().getBody(String.class)));
                }
            });
            var flow = new MsgFlow("WireMapping", List.of(
                    new FlowNode("Input", "MQInput", "Input", Map.of("queueName", "IN"), List.of()),
                    new FlowNode("Mapping", "Mapping", "Mapping", Map.of("mappingBean", generated.mapper().beanName()), List.of()),
                    new FlowNode("Output", "MQOutput", "Output", Map.of("queueName", "OUT"), List.of())),
                    List.of(new FlowConnection("Input", "out", "Mapping", "in"), new FlowConnection("Mapping", "out", "Output", "in")));
            String route = new RouteGenerator(flow).generate().xml().replaceAll("jms:([^?]+)\\?transacted=true&amp;disableReplyTo=true", "direct:$1")
                    .replace("jms:", "direct:");
            assertTrue(route.contains("bean:" + generated.mapper().beanName()));
            PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString("WireMapping-route.xml", route));
            camel.start();
            try (var producer = camel.createProducerTemplate()) {
                Exchange result = producer.request("direct:IN", exchange -> {
                    exchange.getIn().setBody(validXml());
                    exchange.getIn().setHeader("JMSCorrelationID", "correlation-42");
                    exchange.getIn().setHeader("ApplicationHeader", "keep");
                });
                assertNull(result.getException());
                assertEquals("correlation-42", result.getMessage().getHeader("JMSCorrelationID"));
                assertEquals("keep", result.getMessage().getHeader("ApplicationHeader"));
                assertEquals(1, deliveries.size());
                String xml = result.getMessage().getBody(String.class);
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                var root = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))).getDocumentElement();
                assertEquals("WireResult", root.getLocalName());
                assertEquals("urn:wire", root.getNamespaceURI());
                assertEquals("A & B", root.getElementsByTagNameNS("urn:wire", "DisplayName").item(0).getTextContent());
                assertEquals("123.45", root.getElementsByTagNameNS("urn:wire", "Amount").item(0).getTextContent());
                assertEquals("9223372036854775807", root.getElementsByTagNameNS("urn:wire", "Count").item(0).getTextContent());
                assertEquals("true", root.getElementsByTagNameNS("urn:wire", "Active").item(0).getTextContent());
                assertEquals("Москва", root.getElementsByTagNameNS("urn:wire", "Town").item(0).getTextContent());
                assertEquals("true", ((org.w3c.dom.Element) root.getElementsByTagNameNS("urn:wire", "Note").item(0))
                        .getAttributeNS(javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"));
                assertEquals(0, root.getElementsByTagNameNS("urn:wire", "Extra").getLength());
            }
        }
    }

    @Test void freshProcessorRejectsInvalidWireXmlAtomically() throws Exception {
        try (Generated generated = compile(); var camel = new DefaultCamelContext()) {
            Object processor = generated.spring().getBean(generated.mapper().beanName());
            for (Object invalid : List.of(
                    validXml().replace("<p:Active>1</p:Active>", "<p:Active>yes</p:Active>"),
                    validXml().replace("9223372036854775807", "9223372036854775808"),
                    validXml().replace("123.45", "1e2"),
                    validXml().replace("<p:Amount>123.45</p:Amount>", ""),
                    validXml().replace("<p:Name>A &amp; B</p:Name>", "<p:Name>A</p:Name><p:Name>B</p:Name>"),
                    validXml().replace("urn:wire", "urn:wrong"),
                    validXml().replace("<p:Name>", "<p:Name code=\"unsupported\">"),
                    "<!DOCTYPE WireOrder SYSTEM 'file:///nonexistent/iib-mapping.dtd'>" + validXml(),
                    Map.of("Name", "not XML"))) {
                Exchange exchange = new DefaultExchange(camel);
                exchange.getIn().setBody(invalid);
                exchange.getIn().setHeader("JMSCorrelationID", "unchanged");
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> processor.getClass().getMethod("process", Exchange.class).invoke(processor, exchange));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                assertSame(invalid, exchange.getIn().getBody());
                assertEquals("unchanged", exchange.getIn().getHeader("JMSCorrelationID"));
            }
        }
    }

    @Test void unqualifiedChildrenKeepTheirNamespaceAndOptionalAbsentFieldsStayAbsent() throws Exception {
        var model = new XmlBeanMapping.Model("QualifiedRoot", "urn:root", SimpleBean.class,
                List.of(new XmlBeanMapping.Field("OriginalName", "", "value", String.class, false, false, null)));
        SimpleBean parsed = (SimpleBean) XmlBeanMapping.read("<QualifiedRoot xmlns='urn:root'><OriginalName xmlns=''>x</OriginalName></QualifiedRoot>", model);
        assertEquals("x", parsed.getValue());
        String serialized = XmlBeanMapping.write(parsed, model);
        assertTrue(serialized.contains("xmlns=\"\""));
        assertEquals("x", ((SimpleBean) XmlBeanMapping.read(serialized, model)).getValue());
        assertFalse(XmlBeanMapping.write(new SimpleBean(), model).contains("OriginalName"));
        assertThrows(IllegalArgumentException.class, () -> XmlBeanMapping.read("<QualifiedRoot xmlns='urn:root'><OriginalName>x</OriginalName></QualifiedRoot>", model));
    }

    private Generated compile() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path schema = project.resolve("Wire.xsd");
        Files.writeString(schema, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:wire" elementFormDefault="qualified">
                  <xs:element name="WireOrder"><xs:complexType><xs:sequence>
                    <xs:element name="Name" type="xs:string"/><xs:element name="Amount" type="xs:decimal"/>
                    <xs:element name="Count" type="xs:long"/><xs:element name="Active" type="xs:boolean"/>
                    <xs:element name="Details"><xs:complexType><xs:sequence><xs:element name="City" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
                    <xs:element name="Note" type="xs:string" nillable="true"/>
                    <xs:element name="Extra" type="xs:string" minOccurs="0"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="WireResult"><xs:complexType><xs:sequence>
                    <xs:element name="DisplayName" type="xs:string"/><xs:element name="Amount" type="xs:decimal"/>
                    <xs:element name="Count" type="xs:long"/><xs:element name="Active" type="xs:boolean"/>
                    <xs:element name="Address"><xs:complexType><xs:sequence><xs:element name="Town" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
                    <xs:element name="Note" type="xs:string" nillable="true"/>
                    <xs:element name="Extra" type="xs:string" minOccurs="0"/>
                  </xs:sequence></xs:complexType></xs:element>
                </xs:schema>
                """);
        StringBuilder map = new StringBuilder("""
                <mappingRoot xmlns="http://www.ibm.com/2008/ccl/Mapping" domainID="com.ibm.msl.mapping.xml" domainIDExtension="mb" mainMap="true" version="8.0.5.0">
                <input path="/Wire.xsd"/><output path="/Wire.xsd"/><generation engine="xquery"/>
                <mappingDeclaration name="WireMapping"><input namespace="urn:wire" path="mb:msg(WireOrder,assembly,XMLNSC,Properties)" var="in"/>
                <output namespace="urn:wire" path="mb:msg(WireResult,assembly,XMLNSC,Properties)" var="out"/>
                <move><input path="$in/Properties"/><output path="$out/Properties"/></move>
                """);
        for (String[] fields : List.of(new String[]{"Name", "DisplayName"}, new String[]{"Amount", "Amount"},
                new String[]{"Count", "Count"}, new String[]{"Active", "Active"}, new String[]{"Details/City", "Address/Town"}, new String[]{"Note", "Note"})) {
            map.append("<move><input path=\"$in/WireOrder/").append(fields[0])
                    .append("\"/><output path=\"$out/WireResult/").append(fields[1]).append("\"/></move>\n");
        }
        map.append("</mappingDeclaration></mappingRoot>");
        Path mapFile = project.resolve("WireMapping.map");
        Files.writeString(mapFile, map);
        var schemas = new SchemaParser().parseSchemas(project);
        assertEquals(List.of(), schemas.diagnostics());
        var result = new GraphicalMapParser().parseMap(mapFile, schemas);
        assertTrue(result.supported(), result.diagnostics().toString());
        GeneratedMapper mapper = result.mapper();
        Path sources = Files.createDirectories(temp.resolve("sources"));
        List<Path> files = new ArrayList<>();
        for (SchemaModel model : schemas.models()) files.add(write(sources, model.className(), model.javaSource()));
        files.add(write(sources, mapper.className(), mapper.javaSource()));
        files.add(write(sources, mapper.processorClassName(), mapper.processorJava()));
        Path classes = Files.createDirectories(temp.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "25", "-classpath", System.getProperty("java.class.path"), "-processor", "org.mapstruct.ap.MappingProcessor",
                            "-d", classes.toString()), null, manager.getJavaFileObjectsFromPaths(files)).call(), diagnostics.getDiagnostics().toString());
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setClassLoader(loader);
        context.scan(GeneratedMapper.PACKAGE);
        context.refresh();
        return new Generated(mapper, loader, context);
    }

    private static Path write(Path directory, String name, String java) throws Exception {
        Path path = directory.resolve(name + ".java");
        Files.writeString(path, java);
        return path;
    }

    private static String validXml() {
        return """
                <p:WireOrder xmlns:p="urn:wire" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><p:Name>A &amp; B</p:Name><p:Amount>123.45</p:Amount><p:Count>9223372036854775807</p:Count><p:Active>1</p:Active><p:Details><p:City>Москва</p:City></p:Details><p:Note xsi:nil="true"/><p:Extra>drop</p:Extra></p:WireOrder>
                """;
    }

    private record Generated(GeneratedMapper mapper, URLClassLoader loader, AnnotationConfigApplicationContext spring) implements AutoCloseable {
        @Override public void close() throws Exception { spring.close(); loader.close(); }
    }

    public static class SimpleBean {
        private String value;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
