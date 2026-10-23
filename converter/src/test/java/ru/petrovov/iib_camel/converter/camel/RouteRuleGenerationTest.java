package ru.petrovov.iib_camel.converter.camel;

import ru.petrovov.iib_camel.converter.msgflow.FlowConnection;
import ru.petrovov.iib_camel.converter.msgflow.FlowNode;
import ru.petrovov.iib_camel.converter.msgflow.MsgFlow;
import ru.petrovov.iib_camel.converter.msgflow.RouteRule;
import ru.petrovov.iib_camel.converter.report.UnitStatus;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteRuleGenerationTest {
    private final List<RouteRule> rules = List.of(
            new RouteRule("$Body/Order/amount>=100", "High"),
            new RouteRule("$Root/XMLNSC/Order/status='HIGH'", "Audit"));

    @Test
    void firstUsesFilterTableOrderAndEachRulesOwnTerminal() throws Exception {
        var result = generate(Map.of("distributionMode", "First"), rules);
        Document xml = parse(result.xml());
        var when = xml.getElementsByTagName("when");
        assertEquals(2, when.getLength());
        assertEquals("/Order/amount>=100", ((org.w3c.dom.Element) when.item(0))
                .getElementsByTagName("xpath").item(0).getTextContent());
        assertTrue(when.item(0).getTextContent().contains("/Order/amount>=100"));
        assertEquals("jms:HIGH", ((org.w3c.dom.Element) ((org.w3c.dom.Element) when.item(0))
                .getElementsByTagName("to").item(0)).getAttribute("uri"));
        assertEquals("jms:AUDIT", ((org.w3c.dom.Element) ((org.w3c.dom.Element) when.item(1))
                .getElementsByTagName("to").item(0)).getAttribute("uri"));
        assertEquals(1, xml.getElementsByTagName("otherwise").getLength());
        assertEquals(0, xml.getElementsByTagName("multicast").getLength());
        assertEquals(UnitStatus.CONVERTED, result.statusOf("Route"));
        assertEquals(UnitStatus.PARTIAL, result.statusOf("Unused"));
    }

    @Test
    void allAndDefaultModeUseIndependentOrderedCopiesAndNonmatchDefault() throws Exception {
        for (Map<String, String> properties : List.of(Map.<String, String>of(), Map.of("distributionMode", "All"))) {
            Document xml = parse(generate(properties, rules).xml());
            var multicast = (org.w3c.dom.Element) xml.getElementsByTagName("multicast").item(0);
            assertEquals("true", multicast.getAttribute("stopOnException"));
            assertEquals(3, multicast.getElementsByTagName("pipeline").getLength());
            var predicates = multicast.getElementsByTagName("xpath");
            assertEquals("/Order/amount>=100", predicates.item(0).getTextContent());
            assertEquals("/Order/status='HIGH'", predicates.item(1).getTextContent());
            assertEquals("not((/Order/amount>=100) or (/Order/status='HIGH'))", predicates.item(2).getTextContent());
            assertEquals(0, xml.getElementsByTagName("simple").getLength());
        }
    }

    @Test
    void repeatedTerminalGetsEveryFilterTableRow() throws Exception {
        Document xml = parse(generate(Map.of("distributionMode", "First"), List.of(
                rules.getFirst(), new RouteRule("$Body/Order/amount<0", "High"))).xml());
        var when = xml.getElementsByTagName("when");
        assertEquals(2, when.getLength());
        for (int i = 0; i < when.getLength(); i++) {
            var send = ((org.w3c.dom.Element) when.item(i)).getElementsByTagName("to").item(0);
            assertEquals("jms:HIGH", ((org.w3c.dom.Element) send).getAttribute("uri"));
        }
    }

    @Test
    void rejectsWholeRouteWhenAnyRuleCannotBeTranslated() throws Exception {
        for (RouteRule invalid : List.of(new RouteRule("$Body/Order/n:p=1", "Audit"),
                new RouteRule("$Body/Order/amount>=", "Audit"),
                new RouteRule("$Environment/amount=1", "Audit"),
                new RouteRule("$Body/Order/amount=1", ""))) {
            var result = generate(Map.of(), List.of(rules.getFirst(), invalid));
            assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("Route"));
            Document xml = parse(result.xml());
            assertEquals(1, xml.getElementsByTagName("throwException").getLength());
            assertEquals(0, xml.getElementsByTagName("choice").getLength());
            assertEquals(0, xml.getElementsByTagName("multicast").getLength());
            assertFalse(result.xml().contains("jms:DEFAULT"));
        }
    }

    @Test
    void rejectsMissingRulesAndUnknownDistributionMode() {
        assertEquals(UnitStatus.NOT_SUPPORTED, generate(Map.of(), List.of()).statusOf("Route"));
        assertEquals(UnitStatus.NOT_SUPPORTED,
                generate(Map.of("distributionMode", "Random"), rules).statusOf("Route"));
    }

    @Test
    void escapesPredicateTextAndXmlAttributeValues() throws Exception {
        var result = generate(Map.of(), List.of(new RouteRule("$Body/Order/status=\"A&B<Z\"", "High")));
        Document xml = parse(result.xml());
        assertEquals("/Order/status=\"A&B<Z\"", xml.getElementsByTagName("xpath").item(0).getTextContent());
        var special = new MsgFlow("A&B\".msgflow", List.of(
                new FlowNode("Input---quoted", "MQInput", "", Map.of("queueName", "IN&A<\"B"), List.of()),
                new FlowNode("Output", "MQOutput", "", Map.of("queueName", "OUT&A<\"B"), List.of())),
                List.of(new FlowConnection("Input---quoted", "out", "Output", "in")));
        Document escaped = parse(new RouteGenerator(special).generate().xml());
        assertEquals("A&B\"", ((org.w3c.dom.Element) escaped.getElementsByTagName("route").item(0)).getAttribute("id"));
        assertEquals("jms:IN&A<\"B?transacted=true&disableReplyTo=true",
                ((org.w3c.dom.Element) escaped.getElementsByTagName("from").item(0)).getAttribute("uri"));
        assertEquals("jms:OUT&A<\"B", ((org.w3c.dom.Element) escaped.getElementsByTagName("to").item(0)).getAttribute("uri"));
    }

    private ru.petrovov.iib_camel.converter.report.RouteResult generate(Map<String, String> properties,
                                                                        List<RouteRule> ruleList) {
        var flow = new MsgFlow("Rules.msgflow", List.of(
                new FlowNode("Input", "MQInput", "", Map.of("queueName", "IN"), List.of()),
                new FlowNode("Route", "Route", "", properties, List.of("Audit", "Unused", "High"), ruleList),
                new FlowNode("High", "MQOutput", "", Map.of("queueName", "HIGH"), List.of()),
                new FlowNode("Audit", "MQOutput", "", Map.of("queueName", "AUDIT"), List.of()),
                new FlowNode("Unused", "MQOutput", "", Map.of("queueName", "UNUSED"), List.of()),
                new FlowNode("Default", "MQOutput", "", Map.of("queueName", "DEFAULT"), List.of())),
                List.of(new FlowConnection("Input", "out", "Route", "in"),
                        new FlowConnection("Route", "High", "High", "in"),
                        new FlowConnection("Route", "Audit", "Audit", "in"),
                        new FlowConnection("Route", "Unused", "Unused", "in"),
                        new FlowConnection("Route", "Default", "Default", "in")));
        return new RouteGenerator(flow).generate();
    }

    private Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
