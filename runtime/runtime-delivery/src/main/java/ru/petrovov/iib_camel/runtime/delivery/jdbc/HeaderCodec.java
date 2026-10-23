package ru.petrovov.iib_camel.runtime.delivery.jdbc;

import lombok.RequiredArgsConstructor;
import ru.petrovov.iib_camel.runtime.message.MessageHeader;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

@RequiredArgsConstructor
public final class HeaderCodec {
    private final ObjectMapper mapper;

    public String encode(List<MessageHeader> headers) {
        return mapper.writeValueAsString(headers.stream()
            .map(header -> new StoredHeader(header.name(), header.type(),
                Base64.getEncoder().encodeToString(header.value())))
            .toList());
    }

    public List<MessageHeader> decode(String json) {
        return Arrays.stream(mapper.readValue(json, StoredHeader[].class))
            .map(header -> new MessageHeader(header.name(), header.type(),
                Base64.getDecoder().decode(header.value())))
            .toList();
    }

    public record StoredHeader(String name, String type, String value) {
    }
}
