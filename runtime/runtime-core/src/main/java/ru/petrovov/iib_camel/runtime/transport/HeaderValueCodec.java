package ru.petrovov.iib_camel.runtime.transport;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;

public final class HeaderValueCodec {
    private HeaderValueCodec() { }

    public static Object decode(MessageHeader header) {
        byte[] value = header.value();
        return switch (header.type()) {
            case "STRING" -> string(value);
            case "BOOLEAN" -> {
                length(value, 1);
                if (value[0] != 0 && value[0] != 1) throw new IllegalArgumentException("BOOLEAN header must be 0 or 1");
                yield value[0] == 1;
            }
            case "BYTE" -> { length(value, 1); yield value[0]; }
            case "SHORT" -> { length(value, Short.BYTES); yield ByteBuffer.wrap(value).getShort(); }
            case "INT" -> { length(value, Integer.BYTES); yield ByteBuffer.wrap(value).getInt(); }
            case "LONG" -> { length(value, Long.BYTES); yield ByteBuffer.wrap(value).getLong(); }
            case "FLOAT" -> { length(value, Float.BYTES); yield ByteBuffer.wrap(value).getFloat(); }
            case "DOUBLE" -> { length(value, Double.BYTES); yield ByteBuffer.wrap(value).getDouble(); }
            case "BYTES" -> value;
            default -> throw new IllegalArgumentException("Unsupported header type: " + header.type());
        };
    }

    private static String string(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("STRING header must contain valid UTF-8", e);
        }
    }

    private static void length(byte[] value, int expected) {
        if (value.length != expected) throw new IllegalArgumentException("Invalid binary header length; expected " + expected);
    }
}
