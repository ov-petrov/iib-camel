package ru.petrovov.iib_camel.runtime.transport;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import static org.assertj.core.api.Assertions.*;

class HeaderValueCodecTest {
    @Test void decodesAllJmsPropertyTypesInNetworkByteOrder() {
        assertThat(decode("STRING", new byte[]{65})).isEqualTo("A");
        assertThat(decode("BOOLEAN", new byte[]{1})).isEqualTo(true);
        assertThat(decode("BYTE", new byte[]{7})).isEqualTo((byte) 7);
        assertThat(decode("SHORT", ByteBuffer.allocate(2).putShort((short) 123).array())).isEqualTo((short) 123);
        assertThat(decode("INT", ByteBuffer.allocate(4).putInt(123).array())).isEqualTo(123);
        assertThat(decode("LONG", ByteBuffer.allocate(8).putLong(123L).array())).isEqualTo(123L);
        assertThat(decode("FLOAT", ByteBuffer.allocate(4).putFloat(1.25F).array())).isEqualTo(1.25F);
        assertThat(decode("DOUBLE", ByteBuffer.allocate(8).putDouble(1.25D).array())).isEqualTo(1.25D);
        assertThat((byte[]) decode("BYTES", new byte[]{1, 2})).containsExactly(1, 2);
    }

    @Test void rejectsUnknownTypesMalformedValuesAndInvalidUtf8() {
        assertThatThrownBy(() -> decode("OBJECT", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decode("INT", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decode("BOOLEAN", new byte[]{2})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decode("STRING", new byte[]{(byte) 0xFF})).isInstanceOf(IllegalArgumentException.class);
    }

    private static Object decode(String type, byte[] bytes) { return HeaderValueCodec.decode(new MessageHeader("synthetic", type, bytes)); }
}
