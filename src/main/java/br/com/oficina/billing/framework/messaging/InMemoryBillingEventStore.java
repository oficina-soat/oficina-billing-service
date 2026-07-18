package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.framework.observability.StructuredLog;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final Map<UUID, String> contatosPorOrdemServico = new LinkedHashMap<>();
    private final Map<UUID, List<ApprovalTokenRecord>> tokensPorOrcamento = new LinkedHashMap<>();
    private final Map<String, OffsetDateTime> tokensConsumidos = new LinkedHashMap<>();
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
    public synchronized void registrarContato(UUID ordemServicoId, String clienteEmail) {
        contatosPorOrdemServico.put(ordemServicoId, clienteEmail);
    }

    @Override
    public synchronized java.util.Optional<String> buscarContato(UUID ordemServicoId) {
        return java.util.Optional.ofNullable(contatosPorOrdemServico.get(ordemServicoId));
    }

    @Override
    public synchronized void substituirTokensAprovacao(UUID ordemServicoId, UUID orcamentoId,
            String clienteEmail, List<ApprovalTokenRecord> tokens) {
        tokensPorOrcamento.put(orcamentoId, List.copyOf(tokens));
    }

    @Override
    public synchronized java.util.Optional<ApprovalTokenGrant> buscarTokenAprovacao(String tokenHash) {
        return tokensPorOrcamento.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(token -> Map.entry(entry.getKey(), token)))
                .filter(entry -> entry.getValue().tokenHash().equals(tokenHash))
                .findFirst()
                .map(entry -> new ApprovalTokenGrant(
                        null, entry.getKey(), entry.getValue().action(), entry.getValue().expiresAt(),
                        tokensConsumidos.get(tokenHash)));
    }

    @Override
    public synchronized boolean consumirTokenAprovacao(String tokenHash, OffsetDateTime usadoEm) {
        if (tokensConsumidos.containsKey(tokenHash)
                || buscarTokenAprovacao(tokenHash).filter(token -> token.disponivelEm(usadoEm)).isEmpty()) {
            return false;
        }
        tokensConsumidos.put(tokenHash, usadoEm);
        return true;
    }

    @Override
    public synchronized boolean liberarTokenAprovacao(String tokenHash, OffsetDateTime usadoEm) {
        return tokensConsumidos.remove(tokenHash, usadoEm);
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

    @Override
    public synchronized CompletableFuture<Void> registrarOutboxIdempotente(
            UUID eventId,
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt) {
        if (!outboxEvents.containsKey(eventId)) {
            registrarOutboxRecord(eventId, aggregateId, eventType, topic, payload, correlationId, occurredAt);
        }
        return CompletableFuture.completedFuture(null);
    }

    private synchronized OutboxEventRecord registrarOutboxRecord(
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt) {
        return registrarOutboxRecord(
                UUID.randomUUID(), aggregateId, eventType, topic, payload, correlationId, occurredAt);
    }

    private synchronized OutboxEventRecord registrarOutboxRecord(
            UUID eventId,
            String aggregateId,
            String eventType,
            String topic,
            Map<String, Object> payload,
            String correlationId,
            OffsetDateTime occurredAt) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var effectiveCorrelationId = correlationId(correlationId);
        var event = new OutboxEventRecord(
                eventId,
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
        return listarPendentesParaPublicacao(Integer.MAX_VALUE).stream()
                .map(event -> marcarPublicado(event.eventId()))
                .toList();
    }

    @Override
    public synchronized List<OutboxEventRecord> listarPendentesParaPublicacao(int limit) {
        return outboxEvents.values().stream()
                .filter(event -> STATUS_PENDING.equals(event.status()))
                .sorted(Comparator.comparing(OutboxEventRecord::createdAt))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized OutboxEventRecord marcarPublicado(UUID eventId) {
        var event = buscarOutbox(eventId);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
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
        logEvent("outbox event published", publicado, STATUS_PUBLISHED);
        return publicado;
    }

    @Override
    public synchronized OutboxEventRecord marcarFalhaPublicacao(UUID eventId, String lastError, OffsetDateTime nextAttemptAt, boolean failed) {
        var event = buscarOutbox(eventId);
        var status = failed ? "FAILED" : STATUS_PENDING;
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
        outboxEvents.put(failure.eventId(), failure);
        logEvent("outbox event publication failed", failure, status);
        return failure;
    }

    private OutboxEventRecord buscarOutbox(UUID eventId) {
        var event = outboxEvents.get(eventId);
        if (event == null) {
            throw new IllegalStateException("Evento de Outbox nao encontrado: " + eventId);
        }
        return event;
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
