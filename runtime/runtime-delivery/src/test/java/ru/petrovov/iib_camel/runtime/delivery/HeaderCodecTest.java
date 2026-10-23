package ru.petrovov.iib_camel.runtime.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import tools.jackson.databind.ObjectMapper;

class HeaderCodecTest {
    @Test
    void binaryValuesAndRepeatedNamesSurviveJson() {
        HeaderCodec codec = new HeaderCodec(new ObjectMapper());
        List<MessageHeader> original = List.of(new MessageHeader("repeated", "BYTES", new byte[] {0, -1, 9}),
                MessageHeader.text("repeated", "second"));
        List<MessageHeader> decoded = codec.decode(codec.encode(original));
        assertEquals(2, decoded.size());
        for (int index = 0; index < original.size(); index++) {
            assertEquals(original.get(index).name(), decoded.get(index).name());
            assertEquals(original.get(index).type(), decoded.get(index).type());
            assertArrayEquals(original.get(index).value(), decoded.get(index).value());
        }
    }
}
