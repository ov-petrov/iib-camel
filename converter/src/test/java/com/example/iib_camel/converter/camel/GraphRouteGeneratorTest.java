package com.example.iib_camel.converter.camel;

import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.msgflow.RouteRule;
import com.example.iib_camel.converter.report.RouteResult;
import com.example.iib_camel.converter.report.UnitStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRouteGeneratorTest {
    @Test
    void emitsCanonicalTransitiveSubflowsOnceAndPreservesUnderscores() {
        MsgFlow inner = subflow("Schema_Inner_part", node("Work", "Compute", "Inner"));
        MsgFlow outer = subflow("Schema_Outer_part", ref("Nested", "innerAlias"));
        MsgFlow parent = new MsgFlow("Parent.msgflow", List.of(input("In"), ref("One", "outerAlias"),
                ref("Two", "Schema_Outer_part"), output("End")), List.of(link("In", "One"),
                link("One", "Two"), link("Two", "End")));
        var registry = Map.of("innerAlias", inner, "outerAlias", outer);
        String xml = new RouteGenerator(parent, registry).generate().xml();
        assertEquals(1, occurrences(xml, "<from uri=\"direct:Schema_Inner_part\"/>"));
        assertEquals(1, occurrences(xml, "<from uri=\"direct:Schema_Outer_part\"/>"));
        assertEquals(2, occurrences(xml, "<to uri=\"direct:Schema_Outer_part\"/>"));
        assertTrue(xml.contains("<to uri=\"direct:Schema_Inner_part\"/>"));
        assertTrue(xml.contains("bean:inner"));
    }

    @Test
    void sharesSubflowEmissionSessionAcrossParents() {
        MsgFlow common = subflow("Shared", node("Work", "Compute", "Mod"));
        var emitted = new LinkedHashSet<String>();
        String first = new RouteGenerator(parent("First", "Shared"), Map.of("Shared", common), emitted).generate().xml();
        String second = new RouteGenerator(parent("Second", "Shared"), Map.of("Shared", common), emitted).generate().xml();
        assertEquals(1, occurrences(first + second, "<from uri=\"direct:Shared\"/>"));
        assertTrue(second.contains("<to uri=\"direct:Shared\"/>"));
    }

    @Test
    void standaloneGeneratorCanBeCalledRepeatedly() {
        MsgFlow common = subflow("Shared", node("Work", "Compute", "Mod"));
        var generator = new RouteGenerator(parent("Parent", "Shared"), Map.of("Shared", common));
        assertEquals(generator.generate().xml(), generator.generate().xml());
    }

    @Test
    void preservesNamesInDifferentSchemas() {
        MsgFlow a = subflow("SchemaA_Check", node("Work", "Compute", "ModA"));
        MsgFlow b = subflow("SchemaB_Check", node("Work", "Compute", "ModB"));
        var flow = new MsgFlow("Parent.msgflow", List.of(input("In"), ref("A", "SchemaA_Check"),
                ref("B", "SchemaB_Check")), List.of(link("In", "A"), link("A", "B")));
        String xml = new RouteGenerator(flow, Map.of("SchemaA_Check.subflow", a, "SchemaB_Check.subflow", b)).generate().xml();
        assertTrue(xml.contains("<route id=\"SchemaA_Check\">"));
        assertTrue(xml.contains("<route id=\"SchemaB_Check\">"));
    }

    @Test
    void rejectsAmbiguousAliasesWithDiagnosis() {
        var result = new RouteGenerator(parent("Parent", "Check"),
                Map.of("Check", subflow("First", output("A")), "Check.subflow", subflow("Second", output("B"))))
                .generate();
        assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("Call"));
        assertTrue(result.xml().contains("неоднозначная ссылка"));
    }

    @Test
    void stopsGraphCycleWithReadablePath() {
        var flow = new MsgFlow("Cycle.msgflow", List.of(input("In"), node("A", "Compute", "Mod"),
                node("B", "Compute", "Mod")), List.of(link("In", "A"), link("A", "B"), link("B", "A")));
        var result = new RouteGenerator(flow).generate();
        assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("A"));
        assertTrue(result.xml().contains("A → B → A"));
        assertTrue(result.xml().contains("<throwException"));
        assertFalse(result.xml().contains("<to uri=\"bean:"));
    }

    @Test
    void stopsSubflowCycleWithReadablePath() {
        MsgFlow a = subflow("A", ref("ToB", "B"));
        MsgFlow b = subflow("B", ref("ToA", "A"));
        var result = new RouteGenerator(parent("Parent", "A"), Map.of("A", a, "B", b)).generate();
        assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("Call"));
        assertTrue(result.xml().contains("Parent → A → B → A"));
        assertFalse(result.xml().contains("<from uri=\"direct:"));
    }

    @Test
    void followsAllFanoutConnectionsAndMqOutputContinuation() {
        var flow = new MsgFlow("Fanout.msgflow", List.of(input("In"), output("A"), output("B"), output("Tail")),
                List.of(link("In", "A"), link("In", "B"), link("A", "Tail"), link("B", "Tail")));
        var result = new RouteGenerator(flow).generate();
        assertEquals(2, occurrences(result.xml(), "<to uri=\"jms:Tail\"/>"));
        assertTrue(result.xml().contains("<multicast stopOnException=\"true\" shareUnitOfWork=\"true\">"));
        assertTrue(result.xml().indexOf("jms:A") < result.xml().indexOf("jms:B"));
        assertTrue(result.diagnoses().stream().allMatch(d -> d.status() == UnitStatus.CONVERTED));
    }

    @Test
    void givesEachInputItsOwnRouteId() {
        var flow = new MsgFlow("Inputs.msgflow", List.of(input("One"), input("Two"), output("Out")),
                List.of(link("One", "Out"), link("Two", "Out")));
        String xml = new RouteGenerator(flow).generate().xml();
        assertTrue(xml.contains("<route id=\"Inputs-One\">"));
        assertTrue(xml.contains("<route id=\"Inputs-Two\">"));
    }

    @Test
    void rejectsUnknownTerminalsAndMqInputFailure() {
        var unknown = new MsgFlow("Unknown.msgflow", List.of(input("In"), output("Out")),
                List.of(new FlowConnection("In", "mystery", "Out", "in")));
        var failure = new MsgFlow("InputFailure.msgflow", unknown.nodes(),
                List.of(new FlowConnection("In", "failure", "Out", "in")));
        assertTrue(note(new RouteGenerator(unknown).generate()).contains("неподдерживаемый выходной терминал: mystery"));
        assertTrue(note(new RouteGenerator(failure).generate()).contains("ошибкам чтения транспорта"));
        assertFalse(new RouteGenerator(failure).generate().xml().contains("<doCatch>"));
    }

    @Test
    void walksCompleteCatchChainAndComputeFailureChain() {
        var flow = new MsgFlow("Errors.msgflow", List.of(input("In"), node("Work", "Compute", "Work"),
                node("Recover", "Compute", "Recover"), output("Error"), output("Out")),
                List.of(link("In", "Work"), new FlowConnection("In", "catch", "Recover", "in"),
                        new FlowConnection("Work", "failure", "Recover", "in"), link("Recover", "Error"),
                        link("Work", "Out")));
        var result = new RouteGenerator(flow).generate();
        assertEquals(2, occurrences(result.xml(), "bean:recover"));
        assertEquals(2, occurrences(result.xml(), "<to uri=\"jms:Error\"/>"));
        assertFalse(result.xml().contains("TODO-queue-not-set"));
        assertTrue(result.diagnoses().stream().allMatch(d -> d.status() == UnitStatus.CONVERTED));
    }

    @Test
    void stagesRoutePredicatesBeforeBranchesWithFailureHandler() {
        var route = new FlowNode("R", "Route", "", Map.of(), List.of("Match"),
                List.of(new RouteRule("/Order/Status='HIGH'", "Match"), new RouteRule("/Order", "Match")));
        var flow = new MsgFlow("Routing.msgflow", List.of(input("In"), route, output("Match"), output("Error")),
                List.of(link("In", "R"), new FlowConnection("R", "Match", "Match", "in"),
                        new FlowConnection("R", "Failure", "Error", "in")));
        String xml = new RouteGenerator(flow).generate().xml();
        assertEquals(2, occurrences(xml, "<xpath resultType=\"java.lang.Boolean\">"));
        assertTrue(xml.indexOf("</doTry>") < xml.indexOf("jms:Match"));
        assertFalse(xml.contains("<onException"));
    }

    @Test
    void rejectsMultiInputOutputAndRepeatedPropagationInSubflows() {
        var multi = new MsgFlow("Multi.subflow", List.of(node("In1", "Input", ""), node("In2", "Input", ""),
                node("Out", "Output", "")), List.of(link("In1", "Out"), link("In2", "Out")));
        assertEquals(UnitStatus.NOT_SUPPORTED,
                new RouteGenerator(parent("Parent", "Multi"), Map.of("Multi", multi)).generate().statusOf("Call"));
        var fanout = new MsgFlow("Fanout.subflow", List.of(node("In", "Input", ""), output("One"),
                output("Two"), node("Out", "Output", "")), List.of(link("In", "One"), link("In", "Two"),
                link("One", "Out"), link("Two", "Out")));
        assertTrue(note(new RouteGenerator(fanout).inlineResult()).contains("многократная пропагация"));
    }

    @Test
    void unresolvedSubflowStillDiagnosesContinuation() {
        var result = new RouteGenerator(parent("Parent", "Missing")).generate();
        assertEquals(UnitStatus.NOT_SUPPORTED, result.statusOf("Call"));
        assertEquals(UnitStatus.CONVERTED, result.statusOf("End"));
        assertTrue(result.xml().contains("jms:End"));
    }

    private static String note(RouteResult result) {
        return result.diagnoses().stream().map(d -> d.note()).collect(java.util.stream.Collectors.joining("; "));
    }

    private static MsgFlow parent(String name, String reference) {
        return new MsgFlow(name + ".msgflow", List.of(input("In"), ref("Call", reference), output("End")),
                List.of(link("In", "Call"), link("Call", "End")));
    }

    private static MsgFlow subflow(String name, FlowNode work) {
        return new MsgFlow(name + ".subflow", List.of(node("Input", "Input", ""), work, node("Output", "Output", "")),
                List.of(link("Input", work.id()), link(work.id(), "Output")));
    }

    private static FlowNode ref(String id, String target) {
        return new FlowNode(id, "Subflow", "", Map.of("subflowName", target), List.of());
    }

    private static FlowNode input(String id) {
        return new FlowNode(id, "MQInput", "", Map.of("queueName", id), List.of());
    }

    private static FlowNode output(String id) {
        return new FlowNode(id, "MQOutput", "", Map.of("queueName", id), List.of());
    }

    private static FlowNode node(String id, String kind, String module) {
        return new FlowNode(id, kind, "", Map.of("computeExpression", "esql://routine/#" + module + ".Main"), List.of());
    }

    private static FlowConnection link(String from, String to) {
        return new FlowConnection(from, "out", to, "in");
    }

    private static int occurrences(String value, String expected) {
        return (value.length() - value.replace(expected, "").length()) / expected.length();
    }
}
