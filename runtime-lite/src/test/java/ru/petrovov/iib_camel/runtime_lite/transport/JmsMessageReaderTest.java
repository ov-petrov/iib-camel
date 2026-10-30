package ru.petrovov.iib_camel.runtime_lite.transport;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.ObjectMessage;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.runtime_lite.transport.config.ListenerProperties;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JmsMessageReaderTest {

    private final ListenerProperties properties = new ListenerProperties();
    private final JmsMessageReader reader = new JmsMessageReader(properties);

    @Test
    void readsUtf8TextAndApplicationHeaders() throws Exception {
        var message = mock(TextMessage.class);
        prepare(message);
        when(message.getText()).thenReturn("<value>Привет</value>");
        when(message.getPropertyNames()).thenReturn(Collections.enumeration(List.of("source", "JMSXDeliveryCount")));
        when(message.getObjectProperty("source")).thenReturn("sample");
        when(message.getJMSCorrelationID()).thenReturn("correlation");

        var result = reader.read(message);

        assertThat(result.id()).isEqualTo("ID:input");
        assertThat(result.body()).isEqualTo("<value>Привет</value>");
        assertThat(result.headers()).containsEntry("source", "sample")
            .containsEntry(MessageHeaders.CORRELATION_ID, "correlation")
            .doesNotContainKey("JMSXDeliveryCount");
    }

    @Test
    void rejectsBytesMessage() throws Exception {
        var message = mock(BytesMessage.class);
        prepare(message);

        assertThatThrownBy(() -> reader.read(message)).isInstanceOf(JMSException.class)
            .hasMessageContaining("Only TextMessage");
    }

    @Test
    void enforcesLimitInUtf8Bytes() throws Exception {
        properties.setMaxBodyBytes(3);
        var message = mock(TextMessage.class);
        prepare(message);
        when(message.getText()).thenReturn("Пр");

        assertThatThrownBy(() -> reader.read(message)).isInstanceOf(JMSException.class)
            .hasMessageContaining("size limit");
    }

    @Test
    void requiresStableMessageIdentityAndRejectsObjectMessages() throws Exception {
        var text = mock(TextMessage.class);
        prepare(text);
        when(text.getText()).thenReturn("text");
        when(text.getJMSMessageID()).thenReturn(null);
        var object = mock(ObjectMessage.class);
        prepare(object);

        assertThatThrownBy(() -> reader.read(text)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(object)).isInstanceOf(JMSException.class)
            .hasMessageContaining("Only TextMessage");
    }

    private void prepare(Message message) throws JMSException {
        when(message.getJMSMessageID()).thenReturn("ID:input");
        when(message.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
    }

}
