package ru.petrovov.iib_camel.runtime_lite.jdbc;

import ru.petrovov.iib_camel.runtime_lite.message.MessageBody;

public record BodyColumns(String text, String xml) {

    public static BodyColumns of(MessageBody body) {
        return switch (body.format()) {
            case TEXT -> new BodyColumns(body.text(), null);
            case XML -> new BodyColumns(null, body.text());
        };
    }

    public MessageBody toMessageBody() {
        return xml == null ? MessageBody.text(text) : new MessageBody(xml, MessageBody.Format.XML);
    }
}
