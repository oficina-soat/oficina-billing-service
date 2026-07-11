package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.framework.observability.StructuredLog;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@ApplicationScoped
public class BillingEventStore {
    private static final Logger LOG = Logger.getLogger(BillingEventStore.class);
    private static final String PRODUCER = "oficina-billing-service";

    private final Map<UUID, LinkedHashMap<UUID, ItemOrcamento>> itensPorOrdemServico = new LinkedHashMap<>();
    private final Map<UUID, OutboxEventRecord> outboxEvents = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> consumedEventIds = new LinkedHashSet<>();

    public synchronized List<ItemOrcamento> snapshotFinanceiro(UUID ordemServicoId) {
        var itens = itensPorOrdemServico.get(ordemServicoId);
        if (itens == null) {
            return List.of();
        }
        return List.copyOf(itens.values());
    }

    public synchronized boolean registrarEventoConsumido(UUID eventId) {
        return consumedEventIds.add(eventId);
    }

    public synchronized boolean eventoConsumido(UUID eventId) {
        return consumedEventIds.contains(eventId);
    }

    public synchronized void registrarItem(UUID ordemServicoId, ItemOrcamento item) {
        itensPorOrdemServico
                .computeIfAbsent(ordemServicoId, ignored -> new LinkedHashMap<>())
                .put(item.itemId(), item);
    }

    public synchronized OutboxEventRecord registrarOutbox(
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
                "PENDING",
                effectiveCorrelationId,
                occurredAt == null ? now : occurredAt,
                now,
                null,
                0,
                null);
        outboxEvents.put(event.eventId(), event);
        logEvent("outbox event registered", event, "PENDING");
        return event;
    }

    public synchronized List<OutboxEventRecord> listarOutbox() {
        return outboxEvents.values().stream()
                .sorted(Comparator.comparing(OutboxEventRecord::createdAt))
                .toList();
    }

    public synchronized List<OutboxEventRecord> publicarPendentes() {
        var publicados = new ArrayList<OutboxEventRecord>();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (var event : new ArrayList<>(outboxEvents.values())) {
            if (!"PENDING".equals(event.status())) {
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
                    "PUBLISHED",
                    event.correlationId(),
                    event.occurredAt(),
                    event.createdAt(),
                    now,
                    event.attempts() + 1,
                    null);
            outboxEvents.put(publicado.eventId(), publicado);
            publicados.add(publicado);
            logEvent("outbox event published", publicado, "PUBLISHED");
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

    public ItemOrcamento itemPeca(Map<String, Object> peca) {
        var pecaId = uuid(peca.get("pecaId"));
        return new ItemOrcamento(
                TipoItemOrcamento.PECA,
                pecaId,
                pecaId,
                texto(peca.get("nome"), "Peca sem nome"),
                decimal(peca.get("quantidade")),
                decimal(peca.get("valorUnitario")),
                decimal(peca.get("valorTotal")));
    }

    public ItemOrcamento itemServico(Map<String, Object> servico) {
        var servicoId = uuid(servico.get("servicoId"));
        return new ItemOrcamento(
                TipoItemOrcamento.SERVICO,
                servicoId,
                servicoId,
                texto(servico.get("nome"), "Servico sem nome"),
                decimal(servico.get("quantidade")),
                decimal(servico.get("valorUnitario")),
                decimal(servico.get("valorTotal")));
    }

    private UUID uuid(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("UUID obrigatorio no payload do evento.");
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Valor numerico obrigatorio no payload do evento.");
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private String texto(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
