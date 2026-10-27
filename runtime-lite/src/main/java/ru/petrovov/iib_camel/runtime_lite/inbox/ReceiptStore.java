package ru.petrovov.iib_camel.runtime_lite.inbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.petrovov.iib_camel.runtime_lite.jdbc.DeliveryTables;

@Repository
@RequiredArgsConstructor
public class ReceiptStore {

    private final JdbcTemplate jdbc;
    private final DeliveryTables tables;

    boolean claim(String routeId, String messageId) {
        return jdbc.update("INSERT INTO " + tables.receipts() + """
                (route_id, message_id) VALUES (?, ?)
                ON CONFLICT (route_id, message_id) DO NOTHING
                """, routeId, messageId) == 1;
    }
}
