package br.com.oficina.billing.core.interfaces.sender;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface OutboxEventSender {
    CompletableFuture<Void> registrarOutbox(
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt);
}
