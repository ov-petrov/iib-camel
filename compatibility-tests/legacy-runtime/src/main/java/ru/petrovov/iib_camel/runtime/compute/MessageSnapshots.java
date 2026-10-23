package ru.petrovov.iib_camel.runtime.compute;

import org.apache.camel.Exchange;

import java.util.*;

public final class MessageSnapshots {
    private static final String KEY = MessageSnapshots.class.getName() + ".stack";

    public static void capture(Exchange exchange) {
        var stack = new ArrayList<>(stack(exchange));
        stack.add(new Snapshot(exchange.getIn().getBody(),
                Collections.unmodifiableMap(new LinkedHashMap<>(exchange.getIn().getHeaders()))));

        exchange.setProperty(KEY, List.copyOf(stack));
    }

    public static void restore(Exchange exchange) {
        List<Snapshot> stack = stack(exchange);
        if (stack.isEmpty()) throw new IllegalStateException("No message snapshot for this handler");
        Snapshot snapshot = stack.getLast();
        exchange.getIn().setBody(snapshot.body());
        exchange.getIn().setHeaders(new LinkedHashMap<>(snapshot.headers()));
    }

    public static void release(Exchange exchange) {
        List<Snapshot> stack = stack(exchange);
        if (stack.size() <= 1) exchange.removeProperty(KEY);
        else exchange.setProperty(KEY, List.copyOf(stack.subList(0, stack.size() - 1)));
    }

    @SuppressWarnings("unchecked")
    private static List<Snapshot> stack(Exchange exchange) {
        Object value = exchange.getProperty(KEY);
        return value == null ? List.of() : (List<Snapshot>) value;
    }

    private record Snapshot(Object body, Map<String, Object> headers) { }
}
