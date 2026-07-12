package br.com.oficina.billing.framework.idempotency;

import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;

class PostgresIdempotencyStore implements IdempotencyStore {
    private static final String SQL_SELECT = """
            SELECT scope, idempotency_key, request_hash, processing_status, response_status, response_body,
                   correlation_id, request_id, created_at, updated_at, expires_at
            FROM idempotency_record
            WHERE scope = ? AND idempotency_key = ?
            """;
    private static final String SQL_INSERT_PROCESSING = """
            INSERT INTO idempotency_record (
                scope,
                idempotency_key,
                request_hash,
                processing_status,
                response_status,
                response_body,
                correlation_id,
                request_id,
                created_at,
                updated_at,
                expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (scope, idempotency_key) DO NOTHING
            """;
    private static final String SQL_COMPLETE = """
            UPDATE idempotency_record
            SET processing_status = ?, response_status = ?, response_body = ?, updated_at = ?
            WHERE scope = ? AND idempotency_key = ?
            """;

    private final DataSource dataSource;

    PostgresIdempotencyStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SQL_SELECT)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public IdempotencyRecord createProcessing(
            String scope,
            String key,
            String requestHash,
            String correlationId,
            String requestId,
            OffsetDateTime expiresAt) {
        var agora = OffsetDateTime.now(ZoneOffset.UTC);
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SQL_INSERT_PROCESSING)) {
            statement.setString(1, scope);
            statement.setString(2, key);
            statement.setString(3, requestHash);
            statement.setString(4, ProcessingStatus.PROCESSING.name());
            statement.setNull(5, Types.INTEGER);
            statement.setNull(6, Types.VARCHAR);
            statement.setString(7, correlationId);
            statement.setString(8, requestId);
            statement.setObject(9, agora);
            statement.setObject(10, agora);
            statement.setObject(11, expiresAt);
            statement.executeUpdate();
            return find(scope, key).orElseThrow(() -> new IllegalStateException("Registro de idempotencia nao persistido."));
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public void complete(
            String scope,
            String key,
            ProcessingStatus processingStatus,
            int responseStatus,
            String responseBody) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SQL_COMPLETE)) {
            statement.setString(1, processingStatus.name());
            statement.setInt(2, responseStatus);
            statement.setString(3, responseBody);
            statement.setObject(4, OffsetDateTime.now(ZoneOffset.UTC));
            statement.setString(5, scope);
            statement.setString(6, key);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private IdempotencyRecord toRecord(ResultSet resultSet) throws SQLException {
        return new IdempotencyRecord(
                resultSet.getString("scope"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_hash"),
                ProcessingStatus.valueOf(resultSet.getString("processing_status")),
                integer(resultSet, "response_status"),
                resultSet.getString("response_body"),
                resultSet.getString("correlation_id"),
                resultSet.getString("request_id"),
                offsetDateTime(resultSet, "created_at"),
                offsetDateTime(resultSet, "updated_at"),
                offsetDateTime(resultSet, "expires_at"));
    }

    private Integer integer(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private OffsetDateTime offsetDateTime(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column);
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        throw new SQLException("Coluna " + column + " nao pode ser convertida para OffsetDateTime.");
    }

    private IllegalStateException persistenceFailure(SQLException exception) {
        return new IllegalStateException("Falha ao acessar PostgreSQL de idempotencia do billing.", exception);
    }
}
