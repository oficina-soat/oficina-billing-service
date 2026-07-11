package br.com.oficina.billing.framework.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

final class JdbcBillingRepositorySupport {
    private JdbcBillingRepositorySupport() {
    }

    static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, UUID.class);
    }

    static OffsetDateTime offsetDateTime(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column);
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        throw new SQLException("Coluna " + column + " nao pode ser convertida para OffsetDateTime.");
    }

    static IllegalStateException persistenceFailure(SQLException exception) {
        return new IllegalStateException("Falha ao acessar PostgreSQL do billing.", exception);
    }
}
