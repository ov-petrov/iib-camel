package ru.petrovov.iib_camel.runtime.delivery;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import tools.jackson.databind.ObjectMapper;

final class HeaderCodec {
    private final ObjectMapper mapper;

    HeaderCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String encode(List<MessageHeader> headers) {
        return mapper.writeValueAsString(headers.stream()
                .map(header -> new StoredHeader(header.name(), header.type(),
                        Base64.getEncoder().encodeToString(header.value())))
                .toList());
    }

    List<MessageHeader> decode(String json) {
        return Arrays.stream(mapper.readValue(json, StoredHeader[].class))
                .map(header -> new MessageHeader(header.name(), header.type(),
                        Base64.getDecoder().decode(header.value())))
                .toList();
    }

    public record StoredHeader(String name, String type, String value) {}
}
