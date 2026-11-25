package ru.petrovov.iib_camel.runtime_lite.config.inbox;

import jakarta.jms.JMSException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.annotation.JmsListenerConfigurer;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpointRegistrar;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import ru.petrovov.iib_camel.runtime_lite.config.EnrichmentProperties;
import ru.petrovov.iib_camel.runtime_lite.config.RoutingProperties;
import ru.petrovov.iib_camel.runtime_lite.enrichment.EnrichmentCatalog;
import ru.petrovov.iib_camel.runtime_lite.enrichment.EnrichmentReplyReceiver;
import ru.petrovov.iib_camel.runtime_lite.inbox.MessageProcessingService;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsMessageReader;

@Configuration(proxyBeanMethods = false)
@EnableJms
@RequiredArgsConstructor
class JmsRoutes implements JmsListenerConfigurer {

    private static final String REPLY_ENDPOINT_PREFIX = "enrichment-reply:";

    private final RoutingProperties properties;
    private final MessageProcessingService processing;
    private final JmsMessageReader reader;
    private final DefaultJmsListenerContainerFactory liteJmsListenerContainerFactory;
    private final EnrichmentCatalog enrichmentCatalog;
    private final EnrichmentProperties enrichmentProperties;
    private final EnrichmentReplyReceiver replies;

    @Override
    public void configureJmsListeners(JmsListenerEndpointRegistrar registrar) {
        properties.getRoutes().forEach((id, route) -> {
            processing.validateRoute(id);
            var endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId(id);
            endpoint.setDestination(route.getInputQueue());
            endpoint.setConcurrency(Integer.toString(route.getConcurrency()));
            endpoint.setMessageListener(message -> {
                try {
                    processing.process(id, reader.read(message));
                } catch (JMSException failure) {
                    throw new IllegalStateException("Cannot read JMS message", failure);
                }
            });
            registrar.registerEndpoint(endpoint, liteJmsListenerContainerFactory);
        });
        enrichmentCatalog.replyQueues().forEach(queue -> registerReplyListener(registrar, queue));
    }

    private void registerReplyListener(JmsListenerEndpointRegistrar registrar, String queue) {
        String id = REPLY_ENDPOINT_PREFIX + queue;
        if (properties.getRoutes().containsKey(id)) {
            throw new IllegalStateException("Input route conflicts with an enrichment reply endpoint");
        }
        var endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId(id);
        endpoint.setDestination(queue);
        endpoint.setConcurrency(Integer.toString(enrichmentProperties.getReplyConcurrency()));
        endpoint.setMessageListener(message -> {
            try {
                replies.receive(queue, message);
            } catch (JMSException failure) {
                throw new IllegalStateException("Cannot read JMS enrichment reply", failure);
            }
        });
        registrar.registerEndpoint(endpoint, liteJmsListenerContainerFactory);
    }
}
