package ru.petrovov.iib_camel.runtime.config;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

abstract class JmsDeliveryContract {
    protected static final String IN = "DEV.IIB.IN";
    protected static final String OUT = "DEV.IIB.OUT";
    protected static final String DLQ = "DEV.IIB.DLQ";
    protected static final String ERROR = "DEV.IIB.ERROR";
    private DefaultCamelContext camel;
    private JmsTemplate client;
    private final List<Integer> attempts = new CopyOnWriteArrayList<>();

    protected abstract ConnectionFactory createConnectionFactory() throws Exception;
    protected abstract void closeBroker() throws Exception;

    @BeforeEach
    void start() throws Exception {
        var factory = createConnectionFactory();
        var properties = new MessagingProperties(2, 10, DLQ, 1);
        client = new JmsTemplate(factory);
        client.setReceiveTimeout(100);
        for (String queue : List.of(IN, OUT, DLQ, ERROR)) {
            while (client.receive(queue) != null) { }
        }
        camel = new DefaultCamelContext();
        camel.addComponent("jms", new CamelMessagingConfig().jmsComponent(factory, properties));
        camel.addRoutes(new JmsDeliveryPolicy(properties));
        camel.getRegistry().bind("attempt", (org.apache.camel.Processor) exchange -> {
            attempts.add(exchange.getProperty(JmsDeliveryPolicy.DELIVERY_COUNT, Integer.class));
            exchange.getIn().setBody("mutated");
            exchange.getIn().setHeader("OriginalHeader", "mutated");
        });
    }

    @AfterEach
    void stop() throws Exception {
        if (camel != null) camel.close();
        closeBroker();
    }

    @Test
    void refusesNontransactionalConsumerBeforeItCanAcknowledgeFailures() throws Exception {
        camel.addRoutes(new RouteBuilder() {
            @Override public void configure() {
                from("jms:" + IN + "?transacted=false").throwException(new IllegalStateException("failure"));
            }
        });
        assertThrows(Exception.class, camel::start);
    }

    @Test
    void unhandledFailureRollsBackEverySendAndRedeliversOriginalThenQuarantines() throws Exception {
        xmlRoute("""
                <process ref="attempt"/>
                <to uri="jms:DEV.IIB.OUT"/>
                <throwException exceptionType="java.lang.IllegalStateException" message="business failure"/>
                """);
        var sent = send();
        var dead = receive(DLQ);
        assertOriginal(dead);
        assertEquals(sent.getJMSMessageID(), dead.getStringProperty("IibOriginalMessageId"));
        assertTrue(Math.abs(sent.getJMSExpiration() - dead.getJMSExpiration()) <= 100,
                "Quarantine must retain the original expiration, allowing broker TTL granularity");
        assertEquals(4, dead.getIntProperty("IibDeliveryCount"));
        assertEquals(List.of(1, 2, 3), attempts);
        assertNull(client.receive(OUT), "Earlier JMS sends must roll back, including the last business attempt");
        assertNull(client.receive(IN));
        assertNull(client.receive(DLQ), "One committed quarantine only");
    }

    @Test
    void successfulRetryCommitsOneOutputAndNoDeadLetter() throws Exception {
        var calls = new AtomicInteger();
        camel.addRoutes(new RouteBuilder() {
            @Override public void configure() {
                from("jms:" + IN).to("jms:" + OUT).process(exchange -> {
                    attempts.add(exchange.getProperty(JmsDeliveryPolicy.DELIVERY_COUNT, Integer.class));
                    if (calls.incrementAndGet() < 3) throw new IllegalStateException("retry");
                });
            }
        });
        camel.start();
        send();
        assertOriginal(receive(OUT));
        assertEquals(List.of(1, 2, 3), attempts);
        assertNull(client.receive(OUT));
        assertNull(client.receive(DLQ));
    }

    @Test
    void successfulLocalHandlerCommitsAndDoesNotEnterGlobalDlq() throws Exception {
        xmlRoute("""
                <doTry>
                  <throwException exceptionType="java.lang.IllegalStateException" message="business failure"/>
                  <doCatch>
                    <exception>java.lang.Exception</exception>
                    <to uri="jms:DEV.IIB.ERROR"/>
                  </doCatch>
                </doTry>
                """);
        send();
        assertOriginal(receive(ERROR));
        assertNull(client.receive(DLQ));
        assertNull(client.receive(IN));
    }

    @Test
    void failingLocalHandlerRollsBackAndFallsThroughToGlobalQuarantine() throws Exception {
        xmlRoute("""
                <doTry>
                  <process ref="attempt"/>
                  <throwException exceptionType="java.lang.IllegalStateException" message="business failure"/>
                  <doCatch>
                    <exception>java.lang.Exception</exception>
                    <to uri="jms:DEV.IIB.ERROR"/>
                    <throwException exceptionType="java.lang.IllegalArgumentException" message="handler failure"/>
                  </doCatch>
                </doTry>
                """);
        send();
        assertOriginal(receive(DLQ));
        assertEquals(List.of(1, 2, 3), attempts);
        assertNull(client.receive(ERROR));
    }

    @Test
    void failureInsideDirectSubrouteRollsBackAtJmsRoot() throws Exception {
        camel.addRoutes(new RouteBuilder() {
            @Override public void configure() {
                from("jms:" + IN).to("direct:internal");
                from("direct:internal").process("attempt").to("jms:" + OUT)
                        .throwException(new IllegalStateException("direct failure"));
            }
        });
        camel.start();
        send();
        assertOriginal(receive(DLQ));
        assertEquals(List.of(1, 2, 3), attempts);
        assertNull(client.receive(OUT));
    }

    @Test
    void failedQuarantineSendRollsBackInputUntilDeadLetterCanBeCommitted() throws Exception {
        var deadLetterSends = new AtomicInteger();
        camel.getComponent("jms", JmsComponent.class).setMessageCreatedStrategy((message, session, exchange, cause) -> {
            if (exchange.getIn().getHeader("IibDeliveryCount") != null && deadLetterSends.incrementAndGet() < 3) {
                throw new IllegalStateException("DLQ temporarily unavailable");
            }
        });
        xmlRoute("""
                <process ref="attempt"/>
                <throwException exceptionType="java.lang.IllegalStateException" message="business failure"/>
                """);
        var sent = send();
        var dead = receive(DLQ);
        assertOriginal(dead);
        assertEquals(sent.getJMSMessageID(), dead.getStringProperty("IibOriginalMessageId"));
        assertEquals(6, dead.getIntProperty("IibDeliveryCount"));
        assertEquals(3, deadLetterSends.get());
        assertEquals(List.of(1, 2, 3), attempts, "Quarantine retries must not execute business logic again");
        assertNull(client.receive(IN));
        assertNull(client.receive(DLQ));
    }

    private void xmlRoute(String steps) throws Exception {
        String xml = "<routes xmlns=\"http://camel.apache.org/schema/spring\"><route id=\"delivery-contract\">"
                + "<from uri=\"jms:" + IN + "\"/>" + steps + "</route></routes>";
        PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString("contract.xml", xml));
        camel.start();
    }

    private Message send() {
        var message = new java.util.concurrent.atomic.AtomicReference<Message>();
        client.setExplicitQosEnabled(true);
        client.setPriority(7);
        client.setTimeToLive(60000);
        client.send(IN, session -> {
            var original = session.createTextMessage("<Order>original</Order>");
            original.setStringProperty("OriginalHeader", "original");
            original.setJMSCorrelationID("contract-correlation");
            message.set(original);
            return original;
        });
        return message.get();
    }

    private Message receive(String queue) {
        client.setReceiveTimeout(15000);
        Message message = client.receive(queue);
        client.setReceiveTimeout(150);
        assertNotNull(message, "Timed out waiting for " + queue);
        return message;
    }

    private void assertOriginal(Message message) throws Exception {
        assertInstanceOf(TextMessage.class, message);
        assertEquals("<Order>original</Order>", ((TextMessage) message).getText());
        assertEquals("original", message.getStringProperty("OriginalHeader"));
        assertEquals("contract-correlation", message.getJMSCorrelationID());
        assertEquals(7, message.getJMSPriority());
        assertEquals(DeliveryMode.PERSISTENT, message.getJMSDeliveryMode());
    }
}
