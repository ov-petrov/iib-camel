package ru.petrovov.iib_camel.runtime.delivery;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

final class PostgresDatabase {
    private PostgresDatabase() {}

    static void require(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                throw new IllegalStateException("Enabled delivery requires PostgreSQL");
            }
        } catch (SQLException exception) {
            throw new CannotGetJdbcConnectionException("Cannot connect to delivery PostgreSQL", exception);
        }
    }
}
