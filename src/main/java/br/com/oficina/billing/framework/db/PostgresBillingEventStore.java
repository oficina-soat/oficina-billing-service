package br.com.oficina.billing.framework.db;

import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.nullableOffsetDateTime;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.offsetDateTime;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.persistenceFailure;
import static br.com.oficina.billing.framework.db.JdbcBillingRepositorySupport.uuid;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import br.com.oficina.billing.framework.messaging.DomainEventEnvelope;
import br.com.oficina.billing.framework.messaging.OutboxEventRecord;
import br.com.oficina.billing.framework.observability.StructuredLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@ApplicationScoped
@IfBuildProperty(name = "oficina.persistence.kind", stringValue = "postgresql", enableIfMissing = true)
public class PostgresBillingEventStore implements BillingEventStore {
    private static final Logger LOG = Logger.getLogger(PostgresBillingEventStore.class);
    private static final String PRODUCER = "oficina-billing-service";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_FAILED = "FAILED";
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private static final String SELECT_SNAPSHOT = """
            SELECT tipo, item_id, referencia_catalogo_id, nome, quantidade, valor_unitario, valor_total
            FROM financeiro_item_projection
            WHERE ordem_de_servico_id = ?
            ORDER BY criado_em, item_id
            """;

    private static final String INSERT_CONSUMED_EVENT = """
            INSERT INTO billing_consumed_event (
                event_id,
                event_type,
                event_version,
                producer,
                aggregate_id,
                occurred_at,
                consumed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String SELECT_CONSUMED_EVENT = """
            SELECT 1
            FROM billing_consumed_event
            WHERE event_id = ?
            """;

    private static final String UPSERT_ITEM = """
            INSERT INTO financeiro_item_projection (
                ordem_de_servico_id,
                item_id,
                tipo,
                referencia_catalogo_id,
                nome,
                quantidade,
                valor_unitario,
                valor_total,
                criado_em,
                atualizado_em
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (ordem_de_servico_id, item_id) DO UPDATE SET
                tipo = EXCLUDED.tipo,
                referencia_catalogo_id = EXCLUDED.referencia_catalogo_id,
                nome = EXCLUDED.nome,
                quantidade = EXCLUDED.quantidade,
                valor_unitario = EXCLUDED.valor_unitario,
                valor_total = EXCLUDED.valor_total,
                atualizado_em = EXCLUDED.atualizado_em
            """;

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox_event (
                id,
                aggregate_id,
                event_type,
                event_version,
                topic,
                producer,
                payload,
                status,
                correlation_id,
                occurred_at,
                created_at,
                published_at,
                attempts,
                last_error
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_OUTBOX = """
            SELECT id, aggregate_id, event_type, event_version, topic, producer, payload::text AS payload,
                   status, correlation_id, occurred_at, created_at, published_at, attempts, last_error
            FROM outbox_event
            ORDER BY created_at
            """;

    private static final String SELECT_PENDING_OUTBOX_FOR_PUBLICATION = """
            SELECT id, aggregate_id, event_type, event_version, topic, producer, payload::text AS payload,
                   status, correlation_id, occurred_at, created_at, published_at, attempts, last_error
            FROM outbox_event
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
            ORDER BY created_at
            LIMIT ?
            """;

    private static final String SELECT_OUTBOX_BY_ID_FOR_UPDATE = """
            SELECT id, aggregate_id, event_type, event_version, topic, producer, payload::text AS payload,
                   status, correlation_id, occurred_at, created_at, published_at, attempts, last_error
            FROM outbox_event
            WHERE id = ?
            FOR UPDATE
            """;

    private static final String MARK_OUTBOX_PUBLISHED = """
            UPDATE outbox_event
            SET status = ?,
                published_at = ?,
                attempts = ?,
                last_error = NULL,
                next_attempt_at = NULL
            WHERE id = ?
            """;

    private static final String MARK_OUTBOX_FAILURE = """
            UPDATE outbox_event
            SET status = ?,
                attempts = ?,
                next_attempt_at = ?,
                last_error = ?,
                published_at = NULL
            WHERE id = ?
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public PostgresBillingEventStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    @Override
    public CompletableFuture<List<ItemOrcamento>> snapshotFinanceiro(UUID ordemServicoId) {
        return CompletableFuture.completedFuture(snapshotFinanceiroBlocking(ordemServicoId));
    }

    private List<ItemOrcamento> snapshotFinanceiroBlocking(UUID ordemServicoId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_SNAPSHOT)) {
            statement.setObject(1, ordemServicoId);
            try (var resultSet = statement.executeQuery()) {
                var itens = new ArrayList<ItemOrcamento>();
                while (resultSet.next()) {
                    itens.add(new ItemOrcamento(
                            TipoItemOrcamento.valueOf(resultSet.getString("tipo")),
                            uuid(resultSet, "item_id"),
                            uuid(resultSet, "referencia_catalogo_id"),
                            resultSet.getString("nome"),
                            resultSet.getBigDecimal("quantidade"),
                            resultSet.getBigDecimal("valor_unitario"),
                            resultSet.getBigDecimal("valor_total")));
                }
                return List.copyOf(itens);
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public boolean registrarEventoConsumido(DomainEventEnvelope envelope) {
        return registrarEventoConsumido(
                envelope.eventId(),
                envelope.eventType(),
                envelope.eventVersion(),
                envelope.producer(),
                envelope.aggregateId(),
                envelope.occurredAt());
    }

    @Override
    public boolean registrarEventoConsumido(UUID eventId) {
        return registrarEventoConsumido(eventId, null, null, null, null, null);
    }

    private boolean registrarEventoConsumido(
            UUID eventId,
            String eventType,
            Integer eventVersion,
            String producer,
            String aggregateId,
            OffsetDateTime occurredAt) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(INSERT_CONSUMED_EVENT)) {
            statement.setObject(1, eventId);
            statement.setString(2, eventType);
            if (eventVersion == null) {
                statement.setNull(3, Types.INTEGER);
            } else {
                statement.setInt(3, eventVersion);
            }
            statement.setString(4, producer);
            statement.setString(5, aggregateId);
            if (occurredAt == null) {
                statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setObject(6, occurredAt);
            }
            statement.setObject(7, OffsetDateTime.now(ZoneOffset.UTC));
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public boolean eventoConsumido(UUID eventId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_CONSUMED_EVENT)) {
            statement.setObject(1, eventId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public void registrarItem(UUID ordemServicoId, ItemOrcamento item) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(UPSERT_ITEM)) {
            statement.setObject(1, ordemServicoId);
            statement.setObject(2, item.itemId());
            statement.setString(3, item.tipo().name());
            if (item.referenciaCatalogoId() == null) {
                statement.setNull(4, Types.OTHER);
            } else {
                statement.setObject(4, item.referenciaCatalogoId());
            }
            statement.setString(5, item.nome());
            statement.setBigDecimal(6, item.quantidade());
            statement.setBigDecimal(7, item.valorUnitario());
            statement.setBigDecimal(8, item.valorTotal());
            statement.setObject(9, now);
            statement.setObject(10, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public CompletableFuture<Void> registrarOutbox(
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt) {
        registrarOutboxBlocking(aggregateId, eventType, topic, payload, correlationId, occurredAt);
        return CompletableFuture.completedFuture(null);
    }

    private OutboxEventRecord registrarOutboxBlocking(
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var event = new OutboxEventRecord(
                UUID.randomUUID(),
                aggregateId,
                eventType,
                1,
                topic,
                PRODUCER,
                payload,
                STATUS_PENDING,
                correlationId(correlationId),
                occurredAt == null ? now : occurredAt,
                now,
                null,
                0,
                null);
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(INSERT_OUTBOX)) {
            statement.setObject(1, event.eventId());
            statement.setString(2, event.aggregateId());
            statement.setString(3, event.eventType());
            statement.setInt(4, event.eventVersion());
            statement.setString(5, event.topic());
            statement.setString(6, event.producer());
            statement.setString(7, toJson(event.payload()));
            statement.setString(8, event.status());
            statement.setString(9, event.correlationId());
            statement.setObject(10, event.occurredAt());
            statement.setObject(11, event.createdAt());
            statement.setObject(12, event.publishedAt());
            statement.setInt(13, event.attempts());
            statement.setString(14, event.lastError());
            statement.executeUpdate();
            logEvent("outbox event registered", event, STATUS_PENDING);
            return event;
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public List<OutboxEventRecord> listarOutbox() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_OUTBOX);
                var resultSet = statement.executeQuery()) {
            var records = new ArrayList<OutboxEventRecord>();
            while (resultSet.next()) {
                records.add(toOutboxEvent(resultSet));
            }
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public List<OutboxEventRecord> publicarPendentes() {
        return listarPendentesParaPublicacao(Integer.MAX_VALUE).stream()
                .map(event -> marcarPublicado(event.eventId()))
                .toList();
    }

    @Override
    public List<OutboxEventRecord> listarPendentesParaPublicacao(int limit) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(SELECT_PENDING_OUTBOX_FOR_PUBLICATION)) {
            statement.setObject(1, OffsetDateTime.now(ZoneOffset.UTC));
            statement.setInt(2, Math.max(1, limit));
            try (var resultSet = statement.executeQuery()) {
                var records = new ArrayList<OutboxEventRecord>();
                while (resultSet.next()) {
                    records.add(toOutboxEvent(resultSet));
                }
                return List.copyOf(records);
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public OutboxEventRecord marcarPublicado(UUID eventId) {
        var publicado = inTransaction(connection -> {
            var event = selectOutboxByIdForUpdate(connection, eventId);
            var updated = new OutboxEventRecord(
                    event.eventId(),
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    event.topic(),
                    event.producer(),
                    event.payload(),
                    STATUS_PUBLISHED,
                    event.correlationId(),
                    event.occurredAt(),
                    event.createdAt(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    event.attempts() + 1,
                    null);
            markPublished(connection, updated);
            return updated;
        });
        logEvent("outbox event published", publicado, STATUS_PUBLISHED);
        return publicado;
    }

    @Override
    public OutboxEventRecord marcarFalhaPublicacao(UUID eventId, String lastError, OffsetDateTime nextAttemptAt, boolean failed) {
        var status = failed ? STATUS_FAILED : STATUS_PENDING;
        var updated = inTransaction(connection -> {
            var event = selectOutboxByIdForUpdate(connection, eventId);
            var failure = new OutboxEventRecord(
                    event.eventId(),
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    event.topic(),
                    event.producer(),
                    event.payload(),
                    status,
                    event.correlationId(),
                    event.occurredAt(),
                    event.createdAt(),
                    null,
                    event.attempts() + 1,
                    lastError);
            markFailure(connection, failure, failed ? null : nextAttemptAt);
            return failure;
        });
        logEvent("outbox event publication failed", updated, status);
        return updated;
    }

    private void markPublished(Connection connection, OutboxEventRecord event) throws SQLException {
        try (var statement = connection.prepareStatement(MARK_OUTBOX_PUBLISHED)) {
            statement.setString(1, event.status());
            statement.setObject(2, event.publishedAt());
            statement.setInt(3, event.attempts());
            statement.setObject(4, event.eventId());
            statement.executeUpdate();
        }
    }

    private void markFailure(Connection connection, OutboxEventRecord event, OffsetDateTime nextAttemptAt) throws SQLException {
        try (var statement = connection.prepareStatement(MARK_OUTBOX_FAILURE)) {
            statement.setString(1, event.status());
            statement.setInt(2, event.attempts());
            statement.setObject(3, nextAttemptAt);
            statement.setString(4, event.lastError());
            statement.setObject(5, event.eventId());
            statement.executeUpdate();
        }
    }

    private OutboxEventRecord selectOutboxByIdForUpdate(Connection connection, UUID eventId) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT_OUTBOX_BY_ID_FOR_UPDATE)) {
            statement.setObject(1, eventId);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toOutboxEvent(resultSet);
                }
                throw new IllegalStateException("Evento de Outbox nao encontrado: " + eventId);
            }
        }
    }

    private OutboxEventRecord toOutboxEvent(java.sql.ResultSet resultSet) throws SQLException {
        return new OutboxEventRecord(
                uuid(resultSet, "id"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                resultSet.getString("topic"),
                resultSet.getString("producer"),
                toPayload(resultSet.getString("payload")),
                resultSet.getString("status"),
                resultSet.getString("correlation_id"),
                offsetDateTime(resultSet, "occurred_at"),
                offsetDateTime(resultSet, "created_at"),
                nullableOffsetDateTime(resultSet, "published_at"),
                resultSet.getInt("attempts"),
                resultSet.getString("last_error"));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Payload da Outbox nao pode ser serializado em JSON.", exception);
        }
    }

    private Map<String, Object> toPayload(String json) {
        try {
            return objectMapper.readValue(json, PAYLOAD_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payload da Outbox nao pode ser lido do JSON persistido.", exception);
        }
    }

    private void logEvent(String message, OutboxEventRecord event, String messageStatus) {
        StructuredLog.info(LOG, message, Map.of(
                "correlationId", event.correlationId(),
                "eventId", event.eventId().toString(),
                "eventType", event.eventType(),
                "eventVersion", event.eventVersion(),
                "topic", event.topic(),
                "producer", event.producer(),
                "aggregateId", event.aggregateId(),
                "messageStatus", messageStatus));
    }

    private String correlationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId.trim();
        }
        var mdcCorrelationId = MDC.get("correlationId");
        if (mdcCorrelationId != null && !mdcCorrelationId.toString().isBlank()) {
            return mdcCorrelationId.toString();
        }
        return "local-" + UUID.randomUUID();
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException _) {
            // The original persistence failure is more useful to callers.
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (var connection = dataSource.getConnection()) {
            return executeInTransaction(connection, operation);
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private <T> T executeInTransaction(Connection connection, SqlOperation<T> operation) throws SQLException {
        var previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            var result = operation.execute(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollback(connection);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
