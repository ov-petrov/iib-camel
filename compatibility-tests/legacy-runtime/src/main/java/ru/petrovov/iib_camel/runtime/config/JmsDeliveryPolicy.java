package ru.petrovov.iib_camel.runtime.config;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Route;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.apache.camel.component.jms.JmsEndpoint;
import org.apache.camel.support.RoutePolicySupport;
import org.springframework.stereotype.Component;

@Component
public class JmsDeliveryPolicy extends RouteConfigurationBuilder {
    public static final String DELIVERY_COUNT = "iib.jms.deliveryCount";
    public static final String MESSAGE_ID = "iib.jms.messageId";
    private final MessagingProperties properties;

    public JmsDeliveryPolicy(MessagingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configuration() {
        getContext().addRoutePolicyFactory((context, routeId, definition) -> new RoutePolicySupport() {
            @Override
            public void onInit(Route route) {
                if (route.getEndpoint() instanceof JmsEndpoint endpoint
                        && (!endpoint.isTransacted() || endpoint.isPubSubDomain() || !endpoint.isDisableReplyTo())) {
                    throw new IllegalStateException("JMS delivery policy requires a transacted InOnly queue consumer: " + route.getId());
                }
            }
        });
        var configuration = routeConfiguration();
        // Retry the whole broker transaction, never just the failing processor.
        configuration.errorHandler(defaultErrorHandler().maximumRedeliveries(0));
        configuration.interceptFrom("jms:*")
                .process(this::captureDelivery)
                .process(this::delayRedelivery)
                .choice()
                    .when(exchange -> deliveryCount(exchange) > (long) properties.maximumRedeliveries() + 1)
                        .setHeader("IibOriginalMessageId", exchangeProperty(MESSAGE_ID))
                        .setHeader("IibDeliveryCount", exchangeProperty(DELIVERY_COUNT))
                        .process(exchange -> exchange.getIn().setHeader("IibOriginalDestination",
                                String.valueOf(exchange.getIn().getHeader("JMSDestination"))))
                        .log(LoggingLevel.ERROR, "JMS attempts exhausted: messageId=${exchangeProperty.iib.jms.messageId}, delivery=${exchangeProperty.iib.jms.deliveryCount}")
                        .to("jms:queue:" + properties.deadLetterQueue() + "?disableReplyTo=true")
                        .stop()
                .end();
    }

    private void captureDelivery(Exchange exchange) {
        Integer count = exchange.getIn().getHeader("JMSXDeliveryCount", Integer.class);
        if (count == null || count < 1) {
            throw new IllegalStateException("JMS provider must expose a positive JMSXDeliveryCount");
        }
        exchange.setProperty(DELIVERY_COUNT, count);
        exchange.setProperty(MESSAGE_ID, exchange.getIn().getHeader("JMSMessageID", String.class));
    }

    private int deliveryCount(Exchange exchange) {
        return exchange.getProperty(DELIVERY_COUNT, Integer.class);
    }

    private void delayRedelivery(Exchange exchange) throws InterruptedException {
        if (deliveryCount(exchange) > 1 && properties.redeliveryDelay() > 0) {
            Thread.sleep(properties.redeliveryDelay());
        }
    }
}
