package br.com.oficina.billing.framework.messaging;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record OutboxEventRecord(
        UUID eventId,
        String aggregateId,
        String eventType,
        int eventVersion,
        String topic,
        String producer,
        Map<String, Object> payload,
        String status,
        String correlationId,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        int attempts,
        String lastError) {

    public OutboxEventRecord {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId da Outbox e obrigatorio.");
        }
        if (isBlank(aggregateId)) {
            throw new IllegalArgumentException("aggregateId da Outbox e obrigatorio.");
        }
        if (isBlank(eventType)) {
            throw new IllegalArgumentException("eventType da Outbox e obrigatorio.");
        }
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion da Outbox deve ser positivo.");
        }
        if (isBlank(topic)) {
            throw new IllegalArgumentException("Topico da Outbox e obrigatorio.");
        }
        if (isBlank(producer)) {
            throw new IllegalArgumentException("Producer da Outbox e obrigatorio.");
        }
        if (isBlank(status)) {
            throw new IllegalArgumentException("Status da Outbox e obrigatorio.");
        }
        if (occurredAt == null || createdAt == null) {
            throw new IllegalArgumentException("Datas da Outbox sao obrigatorias.");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("Tentativas da Outbox nao podem ser negativas.");
        }
        payload = Map.copyOf(payload == null ? Map.of() : new LinkedHashMap<>(payload));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
