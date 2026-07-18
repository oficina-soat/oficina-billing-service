package br.com.oficina.billing.framework.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationalMetricsTest {
    private SimpleMeterRegistry registry;
    private OperationalMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OperationalMetrics(registry, "oficina-billing-service");
    }

    @Test
    void deveRegistrarPersistenciaPorResultadoELatencia() {
        assertEquals("ok", metrics.persistence("postgresql", "ordem_servico", "save", () -> "ok"));
        metrics.persistence("postgresql", "ordem_servico", "delete", () -> {
        });

        assertEquals(1, registry.get("persistence.operations.count")
                .tags(
                        "service", "oficina-billing-service",
                        "database", "postgresql",
                        "resource", "ordem_servico",
                        "operation", "save",
                        "result", "success",
                        "error", "none")
                .counter()
                .count());
        assertEquals(1, registry.get("persistence.operation.duration")
                .tags("operation", "delete", "result", "success")
                .timer()
                .count());
    }

    @Test
    void deveCategorizarFalhasDePersistencia() {
        assertThrows(ConflictFailure.class, () -> metrics.persistence(
                "dynamodb", "estoque", "put", () -> {
                    throw new ConflictFailure();
                }));
        assertThrows(ConnectFailure.class, () -> metrics.persistence(
                "dynamodb", "estoque", "scan", () -> {
                    throw new ConnectFailure();
                }));

        assertEquals(1, registry.get("persistence.operations.count")
                .tags("operation", "put", "result", "failure", "error", "conflict")
                .counter()
                .count());
        assertEquals(1, registry.get("persistence.operations.count")
                .tags("operation", "scan", "result", "failure", "error", "unavailable")
                .counter()
                .count());
    }

    @Test
    void deveAtualizarBacklogETentativasDaOutbox() {
        metrics.observeOutbox(
                Map.of("pagamentoConfirmado", 2L),
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10));
        var startedAt = metrics.startOutboxAttempt("pagamentoConfirmado", "oficina.billing.pagamento-confirmado");
        metrics.outboxPublished(
                "pagamentoConfirmado", "oficina.billing.pagamento-confirmado", startedAt,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2));
        metrics.outboxFailed("pagamentoConfirmado", "oficina.billing.pagamento-confirmado", "publish_failure");

        assertEquals(2, registry.get("outbox.pending.count")
                .tag("eventType", "pagamentoConfirmado")
                .gauge()
                .value());
        assertTrue(registry.get("outbox.oldest.pending.age").gauge().value() >= 9);
        assertEquals(1, registry.get("outbox.publish.attempts.count").counter().count());
        assertEquals(1, registry.get("outbox.published.count").counter().count());
        assertEquals(1, registry.get("outbox.pending.duration").timer().count());
        assertEquals(1, registry.get("outbox.failed.count").counter().count());

        metrics.observeOutbox(Map.of(), null);
        assertEquals(0, registry.get("outbox.pending.count").tag("eventType", "pagamentoConfirmado").gauge().value());
        assertEquals(0, registry.get("outbox.oldest.pending.age").gauge().value());
    }

    @Test
    void deveRegistrarConsumoFalhaEDlqPorFila() {
        var consumedAt = metrics.startSqsProcessing("fila-a", "topico-a");
        metrics.sqsConsumed(
                "fila-a", "topico-a", "eventoA", consumedAt,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2));
        var failedAt = metrics.startSqsProcessing("fila-a", "topico-a");
        metrics.sqsFailed("fila-a", "topico-a", "eventoA", "processing_failure", true, failedAt);

        assertEquals(2, registry.get("messaging.sqs.received.count").tag("queue", "fila-a").counter().count());
        assertEquals(1, registry.get("messaging.events.consumed.count").tag("eventType", "eventoA").counter().count());
        assertEquals(1, registry.get("messaging.events.failed.count")
                .tags("queue", "fila-a", "reason", "processing_failure")
                .counter()
                .count());
        assertEquals(1, registry.get("messaging.dlq.count").tag("queue", "fila-a").counter().count());
        assertEquals(2, registry.get("messaging.events.processing.duration").tag("queue", "fila-a").timer().count());
        assertEquals(1, registry.get("messaging.events.delivery.latency").tag("queue", "fila-a").timer().count());
    }

    @Test
    void deveRegistrarRetriesEConflitosDeIdempotencia() {
        metrics.idempotencyRetry("post", "retryable");
        metrics.idempotencyConflict("post", "payload_mismatch");

        assertEquals(1, registry.get("idempotency.retries.count")
                .tags("operation", "post", "status", "retryable")
                .counter()
                .count());
        assertEquals(1, registry.get("idempotency.conflicts.count")
                .tags("operation", "post", "reason", "payload_mismatch")
                .counter()
                .count());
    }

    private static final class ConflictFailure extends RuntimeException {
    }

    private static final class ConnectFailure extends RuntimeException {
    }
}
