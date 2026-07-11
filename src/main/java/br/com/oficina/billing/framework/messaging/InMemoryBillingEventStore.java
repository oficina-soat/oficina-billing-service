package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.framework.observability.StructuredLog;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@ApplicationScoped
@IfBuildProperty(name = "oficina.persistence.kind", stringValue = "memory")
public class InMemoryBillingEventStore implements BillingEventStore {
    private static final Logger LOG = Logger.getLogger(InMemoryBillingEventStore.class);
    private static final String PRODUCER = "oficina-billing-service";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private final Map<UUID, LinkedHashMap<UUID, ItemOrcamento>> itensPorOrdemServico = new LinkedHashMap<>();
    private final Map<UUID, OutboxEventRecord> outboxEvents = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> consumedEventIds = new LinkedHashSet<>();

    @Override
    public CompletableFuture<List<ItemOrcamento>> snapshotFinanceiro(UUID ordemServicoId) {
        return CompletableFuture.completedFuture(snapshotFinanceiroLocal(ordemServicoId));
    }

    @Override
    public synchronized boolean registrarEventoConsumido(DomainEventEnvelope envelope) {
        return registrarEventoConsumido(envelope.eventId());
    }

    @Override
    public synchronized boolean registrarEventoConsumido(UUID eventId) {
        return consumedEventIds.add(eventId);
    }

    @Override
    public synchronized boolean eventoConsumido(UUID eventId) {
        return consumedEventIds.contains(eventId);
    }

    @Override
    public synchronized void registrarItem(UUID ordemServicoId, ItemOrcamento item) {
        itensPorOrdemServico
                .computeIfAbsent(ordemServicoId, ignored -> new LinkedHashMap<>())
                .put(item.itemId(), item);
    }

    @Override
    public CompletableFuture<Void> registrarOutbox(
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt) {
        registrarOutboxRecord(aggregateId, eventType, topic, payload, correlationId, occurredAt);
        return CompletableFuture.completedFuture(null);
    }

    private synchronized OutboxEventRecord registrarOutboxRecord(
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var effectiveCorrelationId = correlationId(correlationId);
        var event = new OutboxEventRecord(
                UUID.randomUUID(),
                aggregateId,
                eventType,
                1,
                topic,
                PRODUCER,
                payload,
                STATUS_PENDING,
                effectiveCorrelationId,
                occurredAt == null ? now : occurredAt,
                now,
                null,
                0,
                null);
        outboxEvents.put(event.eventId(), event);
        logEvent("outbox event registered", event, STATUS_PENDING);
        return event;
    }

    private synchronized List<ItemOrcamento> snapshotFinanceiroLocal(UUID ordemServicoId) {
        var itens = itensPorOrdemServico.get(ordemServicoId);
        if (itens == null) {
            return List.of();
        }
        return List.copyOf(itens.values());
    }

    @Override
    public synchronized List<OutboxEventRecord> listarOutbox() {
        return outboxEvents.values().stream()
                .sorted(Comparator.comparing(OutboxEventRecord::createdAt))
                .toList();
    }

    @Override
    public synchronized List<OutboxEventRecord> publicarPendentes() {
        var publicados = new ArrayList<OutboxEventRecord>();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (var event : new ArrayList<>(outboxEvents.values())) {
            if (!STATUS_PENDING.equals(event.status())) {
                continue;
            }
            var publicado = new OutboxEventRecord(
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
                    now,
                    event.attempts() + 1,
                    null);
            outboxEvents.put(publicado.eventId(), publicado);
            publicados.add(publicado);
            logEvent("outbox event published", publicado, STATUS_PUBLISHED);
        }
        return publicados;
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
}
