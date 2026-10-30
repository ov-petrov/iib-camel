package ru.petrovov.iib_camel.runtime_lite.message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.petrovov.iib_camel.runtime_lite.transport.Transport;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundMessageTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresDestinationBeforePublication(String destination) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> OutboundMessage.of(Transport.KAFKA, destination, "body"));
    }

    @Test
    void requiresTransportAndBodyButAllowsEmptyText() {
        assertThatNullPointerException().isThrownBy(() -> OutboundMessage.of(null, "sample.output", "body"));
        assertThatNullPointerException().isThrownBy(() -> OutboundMessage.of(Transport.KAFKA, "sample.output", null));
        assertThat(OutboundMessage.of(Transport.JMS, "SAMPLE.OUT", "").body()).isEmpty();
    }

    @Test
    void preservesHeadersWhenProcessorChangesItsOriginalMap() {
        var headers = new HashMap<>(Map.of("sample_header", "initial"));
        var message = new OutboundMessage(Transport.KAFKA, "sample.output", "body", "key", headers);

        headers.put("sample_header", "changed");

        assertThat(message.headers()).containsEntry("sample_header", "initial");
        assertThatThrownBy(() -> message.headers().put("extra", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
