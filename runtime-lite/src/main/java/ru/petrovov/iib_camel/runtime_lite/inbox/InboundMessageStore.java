package ru.petrovov.iib_camel.runtime_lite.inbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.petrovov.iib_camel.runtime_lite.jdbc.BodyColumns;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;
import ru.petrovov.iib_camel.runtime_lite.message.InboundMessage;
import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;
import ru.petrovov.iib_camel.runtime_lite.transport.JmsPayloadFormat;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlProcessingException;
import ru.petrovov.iib_camel.runtime_lite.xml.XmlService;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class InboundMessageStore {

    private final JdbcTemplate jdbc;
    private final DeliveryTables tables;
    private final JsonMapper json;
    private final XmlService xml;

    boolean claim(String routeId, String sourceDestination, String processorId, InboundMessage input,
                  JmsPayloadFormat format) {
        String sql = """
            INSERT INTO %s (route_id, message_id, source_destination, processor_id, body, body_xml, headers, payload_format)
            VALUES (?, ?, ?, ?, ?, XMLPARSE(DOCUMENT ?), CAST(? AS jsonb), ?)
            ON CONFLICT (route_id, message_id) DO NOTHING
            """.formatted(tables.inboundMessages());
        var body = BodyColumns.of(format == JmsPayloadFormat.SOAP_11
            ? MessageBody.text(input.body()) : auditBody(input.body()));
        return jdbc.update(sql, routeId, input.id(), sourceDestination, processorId, body.text(), body.xml(),
            json.writeValueAsString(input.headers()), format.name()) == 1;
    }

    private MessageBody auditBody(String text) {
        try {
            return MessageBody.xml(xml.validate(text));
        } catch (XmlProcessingException invalidXml) {
            return MessageBody.text(text);
        }
    }
}
