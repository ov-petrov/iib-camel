package ru.petrovov.iib_camel.runtime.e2e;

import ru.petrovov.iib_camel.converter.camel.RouteGenerator;
import ru.petrovov.iib_camel.converter.msgflow.FlowConnection;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.msgflow.RouteRule;
import ru.petrovov.iib_camel.converter.report.UnitStatus;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedRouteSemanticsTest {
    @ParameterizedTest
    @CsvSource({"All,100,FIRST|SECOND", "First,100,FIRST", "'',100,FIRST|SECOND",
            "All,0,DEFAULT", "First,0,DEFAULT", "All,101,SECOND"})
    void evaluatesOrderedRulesAgainstOriginalXml(String mode, int amount, String expected) throws Exception {
        var rules = List.of(new RouteRule("$Body/Order/amount=100", "A"),
                new RouteRule("$Body/Order/amount>0", "B"));
        assertEquals(List.of(expected.split("\\|")), execute(mode, rules, "<Order><amount>" + amount + "</amount></Order>"));
    }

    @Test
    void allSendsOncePerMatchingRowEvenWhenTerminalIsShared() throws Exception {
        var rules = List.of(new RouteRule("$Body/Order/amount=100", "A"),
                new RouteRule("$Body/Order/amount>=100", "A"));
        assertEquals(List.of("FIRST", "FIRST"), execute("All", rules, "<Order><amount>100</amount></Order>"));
    }

    @Test
    void unresolvedOrMalformedPredicateFailsWithoutDefaultDelivery() throws Exception {
        for (String pattern : List.of("$Unknown/Order=1", "$Body/p:Order/amount=1", "($Body/Order/amount=1")) {
            var result = new RouteGenerator(flow("All", List.of(new RouteRule(pattern, "A")))).generate();
            assertNotEquals(UnitStatus.CONVERTED, result.statusOf("Router"));
            assertEquals(List.of("ERROR"), execute("All", List.of(new RouteRule(pattern, "A")), "<Order/>"));
        }
    }

    @Test
    void externalDtdIsRejectedByGeneratedXPathRoute() throws Exception {
        String body = "<!DOCTYPE Order SYSTEM 'file:///nonexistent/iib-camel.dtd'><Order><amount>100</amount></Order>";
        assertEquals(List.of("ERROR"), execute("All", List.of(new RouteRule("$Body/Order/amount=100", "A")), body));
    }

    private static List<String> execute(String mode, List<RouteRule> rules, String body) throws Exception {
        var deliveries = new ArrayList<String>();
        try (var camel = new DefaultCamelContext()) {
            camel.addRoutes(new RouteBuilder() {
                @Override public void configure() {
                    for (String queue : List.of("FIRST", "SECOND", "DEFAULT", "ERROR")) {
                        from("direct:" + queue).process(exchange -> {
                            deliveries.add(queue);
                            exchange.getIn().setBody("<Order><amount>0</amount></Order>");
                        });
                    }
                }
            });
            String xml = new RouteGenerator(flow(mode, rules)).generate().xml().replace("jms:", "direct:");
            PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString("Probe-route.xml", xml));
            camel.start();
            try (var producer = camel.createProducerTemplate()) { producer.sendBody("direct:IN", body); }
        }
        return deliveries;
    }

    private static MsgFlow flow(String mode, List<RouteRule> rules) {
        var nodes = List.of(
                new FlowNode("Input", "MQInput", "", Map.of("queueName", "IN"), List.of()),
                new FlowNode("Router", "Route", "", mode.isEmpty() ? Map.of() : Map.of("distributionMode", mode),
                        List.of("A", "B", "Default"), rules),
                output("One", "FIRST"), output("Two", "SECOND"), output("Fallback", "DEFAULT"), output("Error", "ERROR"));
        return new MsgFlow("Probe", nodes, List.of(
                new FlowConnection("Input", "out", "Router", "in"),
                new FlowConnection("Input", "catch", "Error", "in"),
                new FlowConnection("Router", "A", "One", "in"),
                new FlowConnection("Router", "B", "Two", "in"),
                new FlowConnection("Router", "Default", "Fallback", "in")));
    }

    private static FlowNode output(String id, String queue) {
        return new FlowNode(id, "MQOutput", "", Map.of("queueName", queue), List.of());
    }
}
