package br.com.oficina.billing.framework.messaging;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record DomainEventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        OffsetDateTime occurredAt,
        String producer,
        String aggregateId,
        String correlationId,
        Map<String, Object> payload) {

    public DomainEventEnvelope(
            UUID eventId,
            String eventType,
            int eventVersion,
            OffsetDateTime occurredAt,
            String producer,
            String aggregateId,
            Map<String, Object> payload) {
        this(eventId, eventType, eventVersion, occurredAt, producer, aggregateId, null, payload);
    }

    public DomainEventEnvelope {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId e obrigatorio.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType e obrigatorio.");
        }
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion deve ser positivo.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt e obrigatorio.");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer e obrigatorio.");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId e obrigatorio.");
        }
        payload = Map.copyOf(payload == null ? Map.of() : new LinkedHashMap<>(payload));
    }
}
