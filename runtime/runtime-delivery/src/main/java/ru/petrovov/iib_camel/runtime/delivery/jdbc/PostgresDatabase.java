package ru.petrovov.iib_camel.runtime.delivery.jdbc;

import org.springframework.jdbc.CannotGetJdbcConnectionException;

import javax.sql.DataSource;
import java.sql.SQLException;

public final class PostgresDatabase {
    private PostgresDatabase() {
    }

    public static void require(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                throw new IllegalStateException("Enabled delivery requires PostgreSQL");
            }
        } catch (SQLException exception) {
            throw new CannotGetJdbcConnectionException("Cannot connect to delivery PostgreSQL", exception);
        }
    }
}
