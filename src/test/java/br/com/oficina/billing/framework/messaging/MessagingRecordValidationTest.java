package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class MessagingRecordValidationTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-05T12:00:00Z");

    @Test
    void deveValidarCamposObrigatoriosDoEnvelopeDeEvento() {
        assertInvalido("eventId e obrigatorio.", () -> envelope(null, "pagamentoSolicitado", 1, NOW, "oficina-billing-service", "pagamento-1", Map.of()));
        assertInvalido("eventType e obrigatorio.", () -> envelope(UUID.randomUUID(), null, 1, NOW, "oficina-billing-service", "pagamento-1", Map.of()));
        assertInvalido("eventType e obrigatorio.", () -> envelope(UUID.randomUUID(), " ", 1, NOW, "oficina-billing-service", "pagamento-1", Map.of()));
        assertInvalido("eventVersion deve ser positivo.", () -> envelope(UUID.randomUUID(), "pagamentoSolicitado", 0, NOW, "oficina-billing-service", "pagamento-1", Map.of()));
        assertInvalido("occurredAt e obrigatorio.", () -> envelope(UUID.randomUUID(), "pagamentoSolicitado", 1, null, "oficina-billing-service", "pagamento-1", Map.of()));
        assertInvalido("producer e obrigatorio.", () -> envelope(UUID.randomUUID(), "pagamentoSolicitado", 1, NOW, null, "pagamento-1", Map.of()));
        assertInvalido("producer e obrigatorio.", () -> envelope(UUID.randomUUID(), "pagamentoSolicitado", 1, NOW, " ", "pagamento-1", Map.of()));
        assertInvalido("aggregateId e obrigatorio.", () -> envelope(UUID.randomUUID(), "pagamentoSolicitado", 1, NOW, "oficina-billing-service", null, Map.of()));
        assertInvalido("aggregateId e obrigatorio.", () -> envelope(UUID.randomUUID(), "pagamentoSolicitado", 1, NOW, "oficina-billing-service", " ", Map.of()));
    }

    @Test
    void deveNormalizarPayloadDoEnvelopeDeEvento() {
        var eventId = UUID.randomUUID();
        var envelopeSemPayload = envelope(eventId, "pagamentoSolicitado", 1, NOW, "oficina-billing-service", "pagamento-1", null);

        assertTrue(envelopeSemPayload.payload().isEmpty());

        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", "CRIADO");
        var envelope = envelope(UUID.randomUUID(), "pagamentoSolicitado", 1, NOW, "oficina-billing-service", "pagamento-1", payload);
        payload.put("status", "CONFIRMADO");

        assertEquals("CRIADO", envelope.payload().get("status"));
        var envelopePayload = envelope.payload();
        assertThrows(UnsupportedOperationException.class, () -> envelopePayload.put("novoCampo", "valor"));
    }

    @Test
    void deveValidarCamposObrigatoriosDoRegistroDeOutbox() {
        assertOutboxInvalida("eventId da Outbox e obrigatorio.", () -> outbox(null, "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("aggregateId da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), null, "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("aggregateId da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), " ", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("eventType da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", null, 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("eventType da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", " ", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("eventVersion da Outbox deve ser positivo.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 0, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("Topico da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, null, "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("Topico da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, " ", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("Producer da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", null, Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("Producer da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", " ", Map.of(), "PENDING", NOW, NOW, 0));
        assertOutboxInvalida("Status da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), null, NOW, NOW, 0));
        assertOutboxInvalida("Status da Outbox e obrigatorio.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), " ", NOW, NOW, 0));
        assertOutboxInvalida("Datas da Outbox sao obrigatorias.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", null, NOW, 0));
        assertOutboxInvalida("Datas da Outbox sao obrigatorias.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, null, 0));
        assertOutboxInvalida("Tentativas da Outbox nao podem ser negativas.", () -> outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", Map.of(), "PENDING", NOW, NOW, -1));
    }

    @Test
    void deveNormalizarPayloadDoRegistroDeOutbox() {
        var outboxSemPayload = outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", null, "PENDING", NOW, NOW, 0);

        assertTrue(outboxSemPayload.payload().isEmpty());

        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", "CRIADO");
        var outbox = outbox(UUID.randomUUID(), "pagamento-1", "pagamentoSolicitado", 1, "oficina.billing.pagamento-solicitado", "oficina-billing-service", payload, "PENDING", NOW, NOW, 0);
        payload.put("status", "CONFIRMADO");

        assertEquals("CRIADO", outbox.payload().get("status"));
        var outboxPayload = outbox.payload();
        assertThrows(UnsupportedOperationException.class, () -> outboxPayload.put("novoCampo", "valor"));
    }

    private DomainEventEnvelope envelope(
            UUID eventId,
            String eventType,
            int eventVersion,
            OffsetDateTime occurredAt,
            String producer,
            String aggregateId,
            Map<String, Object> payload) {
        return new DomainEventEnvelope(eventId, eventType, eventVersion, occurredAt, producer, aggregateId, payload);
    }

    private OutboxEventRecord outbox(
            UUID eventId,
            String aggregateId,
            String eventType,
            int eventVersion,
            String topic,
            String producer,
            Map<String, Object> payload,
            String status,
            OffsetDateTime occurredAt,
            OffsetDateTime createdAt,
            int attempts) {
        return new OutboxEventRecord(
                eventId,
                aggregateId,
                eventType,
                eventVersion,
                topic,
                producer,
                payload,
                status,
                "correlation-id",
                occurredAt,
                createdAt,
                null,
                attempts,
                null);
    }

    private void assertInvalido(String mensagem, Executable executable) {
        var erro = assertThrows(IllegalArgumentException.class, executable);
        assertEquals(mensagem, erro.getMessage());
    }

    private void assertOutboxInvalida(String mensagem, Executable executable) {
        assertInvalido(mensagem, executable);
    }
}
