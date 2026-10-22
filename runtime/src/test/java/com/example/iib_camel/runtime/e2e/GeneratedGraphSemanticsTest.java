package com.example.iib_camel.runtime.e2e;

import com.example.iib_camel.converter.camel.RouteGenerator;
import com.example.iib_camel.converter.msgflow.FlowConnection;
import com.example.iib_camel.converter.msgflow.FlowNode;
import com.example.iib_camel.converter.msgflow.MsgFlow;
import com.example.iib_camel.converter.msgflow.RouteRule;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedGraphSemanticsTest {
    @Test
    void deliversEveryEdgeFromOneTerminalInDeclarationOrder() throws Exception {
        var flow = flow("Fanout.msgflow", List.of(
                input("Input", "IN"), output("First", "FIRST"), output("Second", "SECOND")), List.of(
                edge("Input", "out", "First"), edge("Input", "out", "Second")));

        assertEquals(List.of("FIRST", "SECOND"), run(flow));
    }

    @Test
    void fanoutBranchesStartWithIndependentCopiesOfTheInputBody() throws Exception {
        var flow = flow("FanoutCopies.msgflow", List.of(
                input("Input", "IN"), output("First", "FIRST"), output("Second", "SECOND")), List.of(
                edge("Input", "out", "First"), edge("Input", "out", "Second")));
        var events = new ArrayList<String>();

        assertEquals(List.of("FIRST", "SECOND"), run(flow, Map.of(), "IN", Map.of(), events,
                Map.of("FIRST", "mutated")));
        assertEquals(List.of("send:FIRST:message", "send:SECOND:message"), events);
    }

    @Test
    void continuesWalkingAfterMqOutput() throws Exception {
        var flow = flow("OutputChain.msgflow", List.of(
                input("Input", "IN"), output("First", "FIRST"), output("Second", "SECOND")), List.of(
                edge("Input", "out", "First"), edge("First", "out", "Second")));

        assertEquals(List.of("FIRST", "SECOND"), run(flow));
    }

    @Test
    void generatesIndependentRoutesForEachMqInput() throws Exception {
        var flow = flow("TwoInputs.msgflow", List.of(
                input("InputA", "IN_A"), input("InputB", "IN_B"),
                output("OutputA", "OUT_A"), output("OutputB", "OUT_B")), List.of(
                edge("InputA", "out", "OutputA"), edge("InputB", "out", "OutputB")));

        assertEquals(List.of("OUT_A"), run(flow, "IN_A"));
        assertEquals(List.of("OUT_B"), run(flow, "IN_B"));
    }

    @Test
    void nestedSubflowsReturnAndDeliverEachOutputOnce() throws Exception {
        var inner = flow("Inner.subflow", List.of(
                node("InnerInput", "Input"), output("InnerOutput", "INNER"), node("InnerSink", "Output")), List.of(
                edge("InnerInput", "out", "InnerOutput"), edge("InnerOutput", "out", "InnerSink")));
        var outer = flow("Outer.subflow", List.of(
                node("OuterInput", "Input"), subflow("InnerRef", "Inner.subflow"),
                output("OuterOutput", "OUTER"), node("OuterSink", "Output")), List.of(
                edge("OuterInput", "out", "InnerRef"), edge("InnerRef", "out", "OuterOutput"), edge("OuterOutput", "out", "OuterSink")));
        var parent = flow("Parent.msgflow", List.of(
                input("ParentInput", "IN"), subflow("OuterRef", "Outer.subflow"),
                output("ParentOutput", "PARENT")), List.of(
                edge("ParentInput", "out", "OuterRef"), edge("OuterRef", "out", "ParentOutput")));

        assertEquals(List.of("INNER", "OUTER", "PARENT"), run(parent,
                Map.of("Outer.subflow", outer, "Inner.subflow", inner), "IN", Map.of()));
    }

    @Test
    void sharedSubflowIsEmittedOnceAcrossParentGeneratorCalls() throws Exception {
        var shared = flow("Shared.subflow", List.of(
                node("SubInput", "Input"), output("SubOutput", "SHARED"), node("SubSink", "Output")), List.of(
                edge("SubInput", "out", "SubOutput"), edge("SubOutput", "out", "SubSink")));
        var subflows = Map.of("Shared.subflow", shared);
        var firstParent = parentUsing("FirstParent.msgflow", "IN_A", "ParentA", "SharedRefA", "Shared.subflow");
        var secondParent = parentUsing("SecondParent.msgflow", "IN_B", "ParentB", "SharedRefB", "Shared.subflow");
        var emittedSubflows = new java.util.LinkedHashSet<String>();
        var deliveries = new ArrayList<String>();
        try (var camel = new DefaultCamelContext()) {
            camel.addRoutes(new RouteBuilder() {
                @Override public void configure() {
                    for (String queue : List.of("SHARED", "PARENT_IN_A", "PARENT_IN_B")) {
                        from("direct:" + queue).process(exchange -> deliveries.add(queue));
                    }
                }
            });
            String firstXml = new RouteGenerator(firstParent, subflows, emittedSubflows).generate().xml()
                    .replace("jms:", "direct:");
            String secondXml = new RouteGenerator(secondParent, subflows, emittedSubflows).generate().xml()
                    .replace("jms:", "direct:");
            PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString("ParentA-route.xml", firstXml));
            PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString("ParentB-route.xml", secondXml));
            camel.start();
            try (var producer = camel.createProducerTemplate()) {
                producer.sendBody("direct:IN_A", "a");
                producer.sendBody("direct:IN_B", "b");
            }
        }
        assertEquals(List.of("SHARED", "PARENT_IN_A", "SHARED", "PARENT_IN_B"), deliveries);
    }

    @Test
    void mqInputCatchInvokesComputeHandlerAndSends() throws Exception {
        var flow = flow("InputCatch.msgflow", List.of(
                input("Input", "IN"), compute("Main", "main"), compute("Handler", "handler"),
                output("Handled", "HANDLED")), List.of(
                edge("Input", "out", "Main"), edge("Input", "catch", "Handler"),
                edge("Handler", "out", "Handled")));
        var events = new ArrayList<String>();
        Processor main = exchange -> {
            events.add("main");
            exchange.getMessage().setBody("partial output");
            exchange.getMessage().setHeader("partial", "discard");
            throw new IllegalStateException("main failed");
        };
        Processor handler = exchange -> {
            events.add("handler");
            assertEquals("message", exchange.getMessage().getBody());
            assertNull(exchange.getMessage().getHeader("partial"));
            exchange.getMessage().setBody("handled");
        };

        assertEquals(List.of("HANDLED"), run(flow, Map.of(), "IN", Map.of(
                "main", main, "handler", handler), events));
        assertEquals(List.of("main", "handler", "send:HANDLED:handled"), events);
    }

    @Test
    void computeFailureRunsLocalHandlerBeforeOuterInputCatch() throws Exception {
        var flow = flow("ComputeFailure.msgflow", List.of(
                input("Input", "IN"), compute("Main", "main"), compute("Recovery", "recovery"),
                output("Recovered", "RECOVERED"), output("OuterCatch", "OUTER_CATCH")), List.of(
                edge("Input", "out", "Main"), edge("Main", "out", "Recovered"),
                edge("Main", "failure", "Recovery"), edge("Recovery", "out", "Recovered"),
                edge("Input", "catch", "OuterCatch")));
        var events = new ArrayList<String>();
        Processor main = exchange -> {
            events.add("main");
            exchange.getMessage().setBody("partial output");
            exchange.getMessage().setHeader("partial", "discard");
            throw new IllegalStateException("main failed");
        };
        Processor recovery = exchange -> {
            events.add("recovery");
            assertEquals("message", exchange.getMessage().getBody());
            assertNull(exchange.getMessage().getHeader("partial"));
        };

        assertEquals(List.of("RECOVERED"), run(flow, Map.of(), "IN", Map.of(
                "main", main, "recovery", recovery), events));
        assertEquals(List.of("main", "recovery", "send:RECOVERED:message"), events);
    }

    @Test
    void failedLocalFailureHandlerFallsThroughToOriginalInputCatch() throws Exception {
        var flow = flow("FailurePrecedence.msgflow", List.of(
                input("Input", "IN"), compute("Main", "main"), compute("Recovery", "recovery"),
                output("Recovered", "RECOVERED"), output("OuterCatch", "OUTER_CATCH")), List.of(
                edge("Input", "out", "Main"), edge("Main", "out", "Recovered"),
                edge("Main", "failure", "Recovery"), edge("Recovery", "out", "Recovered"),
                edge("Input", "catch", "OuterCatch")));
        var events = new ArrayList<String>();
        Processor main = exchange -> {
            events.add("main");
            exchange.getMessage().setBody("partial output");
            exchange.getMessage().setHeader("partial", "discard");
            throw new IllegalStateException("main failed");
        };
        Processor recovery = exchange -> {
            events.add("recovery");
            throw new IllegalArgumentException("recovery failed");
        };

        assertEquals(List.of("OUTER_CATCH"), run(flow, Map.of(), "IN", Map.of(
                "main", main, "recovery", recovery), events));
        assertEquals(List.of("main", "recovery", "send:OUTER_CATCH:message"), events);
    }

    @Test
    void cyclicGraphFailsDeterministicallyWithoutNodeExecution() {
        var flow = flow("Cycle.msgflow", List.of(
                input("Input", "IN"), compute("One", "one"), compute("Two", "two")), List.of(
                edge("Input", "out", "One"), edge("One", "out", "Two"), edge("Two", "out", "One")));
        var firstEvents = new ArrayList<String>();
        var secondEvents = new ArrayList<String>();
        Map<String, Processor> processors = Map.of(
                "one", exchange -> firstEvents.add("one"),
                "two", exchange -> firstEvents.add("two"));

        Throwable first = assertThrows(Exception.class, () -> run(flow, Map.of(), "IN", processors, firstEvents));
        Map<String, Processor> secondProcessors = Map.of(
                "one", exchange -> secondEvents.add("one"),
                "two", exchange -> secondEvents.add("two"));
        Throwable second = assertThrows(Exception.class,
                () -> run(flow, Map.of(), "IN", secondProcessors, secondEvents));
        assertEquals(deepest(first).getClass(), deepest(second).getClass());
        assertEquals(deepest(first).getMessage(), deepest(second).getMessage());
        assertTrue(firstEvents.isEmpty());
        assertTrue(secondEvents.isEmpty());
    }

    @Test
    void downstreamFailureBypassesPredecessorLocalFailureHandler() throws Exception {
        var graph = flow("DownstreamFailure.msgflow", List.of(input("Input", "IN"),
                compute("First", "first"), compute("Second", "second"),
                output("Local", "LOCAL"), output("Catch", "OUTER")), List.of(
                edge("Input", "out", "First"), edge("First", "out", "Second"),
                edge("First", "failure", "Local"), edge("Input", "catch", "Catch")));
        Map<String, Processor> beans = Map.of("first", e -> e.getMessage().setBody("changed"),
                "second", e -> { throw new IllegalStateException("downstream failure"); });
        assertEquals(List.of("OUTER"), run(graph, Map.of(), "IN", beans));
    }

    @Test
    void allRouteRunsEarlierMatchBeforeLaterPredicateFailure() throws Exception {
        assertEquals(List.of("FIRST", "LOCAL_FAILURE"), run(routeWithFailure("All", false)));
    }

    @Test
    void firstRouteSkipsFailingLaterPredicateAfterMatch() throws Exception {
        assertEquals(List.of("FIRST"), run(routeWithFailure("First", false)));
    }

    @Test
    void routeFailureDoesNotCatchBusinessBranchException() throws Exception {
        Map<String, Processor> beans = Map.of("downstream", e -> {
            e.getMessage().setBody("partial");
            throw new IllegalStateException("business failure");
        });
        assertEquals(List.of("OUTER"), run(routeWithFailure("First", true), Map.of(), "IN", beans));
    }

    @Test
    void subflowThatDoesNotReachItsOutputDoesNotContinueParent() throws Exception {
        var sub = flow("Stopped.subflow", List.of(node("Source", "Input"), output("Sent", "INNER"),
                node("Sink", "Output")), List.of(edge("Source", "out", "Sent")));
        var parent = parentUsing("StoppedParent.msgflow", "IN", "ParentOut", "Ref", "Stopped");
        assertEquals(List.of("INNER"), run(parent, Map.of("Stopped", sub), "IN", Map.of()));
    }

    private static MsgFlow routeWithFailure(String mode, boolean failingBranch) {
        var nodes = new ArrayList<>(List.of(input("Input", "IN"),
                new FlowNode("Router", "Route", "", Map.of("distributionMode", mode),
                        List.of("A", "B", "Default", "Failure"),
                        List.of(new RouteRule("true()", "A"), new RouteRule("count('text')", "B"))),
                output("First", "FIRST"), output("Second", "SECOND"), output("Default", "DEFAULT"),
                output("Local", "LOCAL_FAILURE"), output("Catch", "OUTER")));
        var links = new ArrayList<>(List.of(edge("Input", "out", "Router"), edge("Input", "catch", "Catch"),
                edge("Router", "B", "Second"), edge("Router", "Default", "Default"),
                edge("Router", "Failure", "Local")));
        if (failingBranch) {
            nodes.add(compute("Downstream", "downstream"));
            links.add(edge("Router", "A", "Downstream"));
            links.add(edge("Downstream", "out", "First"));
        } else links.add(edge("Router", "A", "First"));
        return flow("RouteErrors.msgflow", nodes, links);
    }

    private static Throwable deepest(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static List<String> run(MsgFlow flow) throws Exception {
        return run(flow, Map.of(), "IN", Map.of(), new ArrayList<>());
    }

    private static List<String> run(MsgFlow flow, String input) throws Exception {
        return run(flow, Map.of(), input, Map.of(), new ArrayList<>());
    }

    private static List<String> run(MsgFlow flow, Map<String, MsgFlow> subflows, String input,
                                    Map<String, Processor> beans) throws Exception {
        return run(flow, subflows, input, beans, new ArrayList<>());
    }

    private static List<String> run(MsgFlow flow, Map<String, MsgFlow> subflows, String input,
                                    Map<String, Processor> beans, List<String> events) throws Exception {
        return run(flow, subflows, input, beans, events, Map.of());
    }

    private static List<String> run(MsgFlow flow, Map<String, MsgFlow> subflows, String input,
                                    Map<String, Processor> beans, List<String> events,
                                    Map<String, String> outputMutations) throws Exception {
        var deliveries = new ArrayList<String>();
        try (var camel = new DefaultCamelContext()) {
            for (var bean : beans.entrySet()) {
                camel.getRegistry().bind(bean.getKey(), bean.getValue());
            }
            camel.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    for (FlowNode node : allNodes(flow, subflows)) {
                        if ("MQOutput".equals(node.kind())) {
                            String queue = node.properties().getOrDefault("queueName", "");
                            from("direct:" + queue).process(exchange -> {
                                deliveries.add(queue);
                                events.add("send:" + queue + ":" + exchange.getMessage().getBody(String.class));
                                if (outputMutations.containsKey(queue)) {
                                    exchange.getMessage().setBody(outputMutations.get(queue));
                                }
                            });
                        }
                    }
                }
            });
            String xml = new RouteGenerator(flow, subflows).generate().xml().replace("jms:", "direct:");
            PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString("GeneratedGraph-route.xml", xml));
            camel.start();
            try (var producer = camel.createProducerTemplate()) {
                producer.sendBody("direct:" + input, flow.name().equals("RouteErrors.msgflow") ? "<Order/>" : "message");
            }
        }
        return deliveries;
    }

    private static List<FlowNode> allNodes(MsgFlow flow, Map<String, MsgFlow> subflows) {
        var result = new ArrayList<>(flow.nodes());
        for (MsgFlow subflow : subflows.values()) result.addAll(subflow.nodes());
        return result;
    }

    private static MsgFlow flow(String name, List<FlowNode> nodes, List<FlowConnection> connections) {
        return new MsgFlow(name, nodes, connections);
    }

    private static MsgFlow parentUsing(String flowName, String inputQueue, String outputId,
                                       String referenceId, String subflowName) {
        return flow(flowName, List.of(input("Input", inputQueue), subflow(referenceId, subflowName),
                output(outputId, "PARENT_" + inputQueue)), List.of(
                edge("Input", "out", referenceId), edge(referenceId, "out", outputId)));
    }

    private static FlowNode input(String id, String queue) {
        return new FlowNode(id, "MQInput", "", Map.of("queueName", queue), List.of());
    }

    private static FlowNode output(String id, String queue) {
        return new FlowNode(id, "MQOutput", "", Map.of("queueName", queue), List.of());
    }

    private static FlowNode compute(String id, String bean) {
        return new FlowNode(id, "Compute", "", Map.of("computeExpression", "module#" + bean + ".Main"), List.of());
    }

    private static FlowNode subflow(String id, String name) {
        return new FlowNode(id, "Subflow", "", Map.of("subflowName", name), List.of());
    }

    private static FlowNode node(String id, String kind) {
        return new FlowNode(id, kind, "", Map.of(), List.of());
    }

    private static FlowConnection edge(String source, String terminal, String target) {
        return new FlowConnection(source, terminal, target, "in");
    }
}
