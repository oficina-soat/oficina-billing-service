package br.com.oficina.billing.framework.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OutboxPublisher {
    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);

    private final BillingEventStore store;
    private final AwsDomainMessagingClient messagingClient;
    private final DomainEventJsonCodec codec;
    private final boolean publisherEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final long backoffBaseMs;

    public OutboxPublisher(BillingEventStore store) {
        this(store, null, null, false, 10, 5, 1000);
    }

    @Inject
    public OutboxPublisher(
            BillingEventStore store,
            AwsDomainMessagingClient messagingClient,
            DomainEventJsonCodec codec,
            @ConfigProperty(name = "oficina.messaging.publisher.enabled", defaultValue = "false") boolean publisherEnabled,
            @ConfigProperty(name = "oficina.messaging.publisher.batch-size", defaultValue = "10") int batchSize,
            @ConfigProperty(name = "oficina.messaging.publisher.max-attempts", defaultValue = "5") int maxAttempts,
            @ConfigProperty(name = "oficina.messaging.publisher.backoff-base-ms", defaultValue = "1000") long backoffBaseMs) {
        this.store = store;
        this.messagingClient = messagingClient;
        this.codec = codec;
        this.publisherEnabled = publisherEnabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
    }

    public List<OutboxEventRecord> publicarPendentes() {
        if (!publisherEnabled) {
            return store.publicarPendentes();
        }
        var publicados = new ArrayList<OutboxEventRecord>();
        for (var event : store.listarPendentesParaPublicacao(batchSize)) {
            try {
                publicar(event);
                publicados.add(store.marcarPublicado(event.eventId()));
            } catch (RuntimeException exception) {
                var attempts = event.attempts() + 1;
                var failed = attempts >= maxAttempts;
                store.marcarFalhaPublicacao(
                        event.eventId(),
                        rootMessage(exception),
                        nextAttempt(attempts),
                        failed);
                LOG.warnf(exception, "Falha ao publicar evento %s no topico %s", event.eventId(), event.topic());
            }
        }
        return List.copyOf(publicados);
    }

    private void publicar(OutboxEventRecord event) {
        if (!DomainMessagingRoutes.isProduced(event.eventType(), event.topic())) {
            throw new IllegalArgumentException("Evento nao produzido pelo oficina-billing-service: " + event.eventType());
        }
        messagingClient.publish(event.topic(), codec.encode(event), Map.of(
                "eventType", event.eventType(),
                "eventVersion", Integer.toString(event.eventVersion()),
                "producer", event.producer(),
                "aggregateId", event.aggregateId(),
                "correlationId", event.correlationId() == null ? event.eventId().toString() : event.correlationId()));
    }

    private OffsetDateTime nextAttempt(int attempts) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusNanos(backoffBaseMs * retryMultiplier(attempts) * 1_000_000L);
    }

    static long retryMultiplier(int attempts) {
        var retryIndex = Math.clamp((long) attempts - 1L, 0L, 10L);
        return 1L << retryIndex;
    }

    private static String rootMessage(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
