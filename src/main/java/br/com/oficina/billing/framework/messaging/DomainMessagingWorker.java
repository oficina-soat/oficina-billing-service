package br.com.oficina.billing.framework.messaging;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@Startup
class DomainMessagingWorker {
    private static final Logger LOG = Logger.getLogger(DomainMessagingWorker.class);

    private final OutboxPublisher outboxPublisher;
    private final SqsDomainEventConsumer sqsConsumer;
    private final boolean workerEnabled;
    private final long pollIntervalMs;
    private ScheduledExecutorService executor;

    DomainMessagingWorker(
            OutboxPublisher outboxPublisher,
            SqsDomainEventConsumer sqsConsumer,
            @ConfigProperty(name = "oficina.messaging.worker.enabled", defaultValue = "false") boolean workerEnabled,
            @ConfigProperty(name = "oficina.messaging.poll-interval-ms", defaultValue = "5000") long pollIntervalMs) {
        this.outboxPublisher = outboxPublisher;
        this.sqsConsumer = sqsConsumer;
        this.workerEnabled = workerEnabled;
        this.pollIntervalMs = pollIntervalMs;
    }

    @PostConstruct
    void start() {
        if (!workerEnabled) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "domain-messaging-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 1000, Math.max(1000, pollIntervalMs), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void tick() {
        try {
            outboxPublisher.publicarPendentes();
            sqsConsumer.consumirDisponiveis();
        } catch (RuntimeException exception) {
            LOG.warn("Falha no ciclo de mensageria de dominio.", exception);
        }
    }
}
