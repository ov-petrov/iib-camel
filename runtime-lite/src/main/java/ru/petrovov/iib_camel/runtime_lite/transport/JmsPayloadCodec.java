package ru.petrovov.iib_camel.runtime_lite.transport;

import lombok.RequiredArgsConstructor;
import ru.petrovov.iib_camel.runtime_lite.config.soap.SoapProperties;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.message.OutboundMessage;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapProcessingException;
import ru.petrovov.iib_camel.runtime_lite.soap.SoapService;

@RequiredArgsConstructor
public class JmsPayloadCodec {

    private final SoapProperties properties;
    private final SoapService soap;
    private final JmsMessageReader reader;

    public JmsPayloadFormat formatFor(String destination) {
        return properties.getPlainJmsDestinations().contains(destination)
            ? JmsPayloadFormat.PLAIN : JmsPayloadFormat.SOAP_11;
    }

    public InboundMessage decode(InboundMessage message, JmsPayloadFormat format) {
        return switch (format) {
            case PLAIN -> message;
            case SOAP_11 -> {
                checkSize(message.body());
                yield new InboundMessage(message.id(), soap.unwrap(message.body()), message.headers());
            }
        };
    }

    public OutboundMessage encode(OutboundMessage message) {
        if (message.transport() != Transport.JMS || formatFor(message.destination()) == JmsPayloadFormat.PLAIN) {
            return message;
        }
        String envelope = soap.wrap(message.body());
        checkSize(envelope);
        return OutboundMessage.of(message.transport(), message.destination(), MessageBody.text(envelope), message.key(),
            message.headers());
    }

    public MessageBody wireBody(InboundMessage original, MessageBody validated, JmsPayloadFormat format) {
        return format == JmsPayloadFormat.SOAP_11 ? MessageBody.text(original.body()) : validated;
    }

    private void checkSize(String body) {
        if (!reader.isBodyWithinLimit(body)) {
            throw new SoapProcessingException("SOAP message exceeds the configured size limit");
        }
    }
}
