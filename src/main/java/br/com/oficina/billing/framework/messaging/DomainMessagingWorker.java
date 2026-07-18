package br.com.oficina.billing.framework.messaging;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
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
    private final long shutdownTimeoutMs;
    private ScheduledExecutorService publisherExecutor;
    private final List<ScheduledExecutorService> consumerExecutors = new ArrayList<>();

    DomainMessagingWorker(
            OutboxPublisher outboxPublisher,
            SqsDomainEventConsumer sqsConsumer,
            @ConfigProperty(name = "oficina.messaging.worker.enabled", defaultValue = "false") boolean workerEnabled,
            @ConfigProperty(name = "oficina.messaging.poll-interval-ms", defaultValue = "5000") long pollIntervalMs) {
        this(outboxPublisher, sqsConsumer, workerEnabled, pollIntervalMs, 5000);
    }

    @Inject
    DomainMessagingWorker(
            OutboxPublisher outboxPublisher,
            SqsDomainEventConsumer sqsConsumer,
            @ConfigProperty(name = "oficina.messaging.worker.enabled", defaultValue = "false") boolean workerEnabled,
            @ConfigProperty(name = "oficina.messaging.poll-interval-ms", defaultValue = "250") long pollIntervalMs,
            @ConfigProperty(name = "oficina.messaging.shutdown-timeout-ms", defaultValue = "5000") long shutdownTimeoutMs) {
        this.outboxPublisher = outboxPublisher;
        this.sqsConsumer = sqsConsumer;
        this.workerEnabled = workerEnabled;
        this.pollIntervalMs = pollIntervalMs;
        this.shutdownTimeoutMs = Math.max(0, shutdownTimeoutMs);
    }

    @PostConstruct
    void start() {
        if (!workerEnabled) {
            return;
        }
        publisherExecutor = executor("outbox-publisher-worker");
        publisherExecutor.scheduleWithFixedDelay(this::publishTick, 0, delayMs(), TimeUnit.MILLISECONDS);
        for (var topic : DomainMessagingRoutes.consumedTopics()) {
            var consumerExecutor = executor("domain-event-consumer-" + DomainMessagingRoutes.physicalName(topic));
            consumerExecutors.add(consumerExecutor);
            consumerExecutor.scheduleWithFixedDelay(() -> consumeTick(topic), 0, delayMs(), TimeUnit.MILLISECONDS);
        }
    }

    private ScheduledExecutorService executor(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private long delayMs() {
        return Math.max(50, pollIntervalMs);
    }

    @PreDestroy
    void stop() {
        if (publisherExecutor != null) {
            shutdown(publisherExecutor);
        }
        consumerExecutors.forEach(this::shutdown);
        consumerExecutors.clear();
    }

    private void shutdown(ScheduledExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException _) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void publishTick() {
        try {
            outboxPublisher.publicarPendentes();
        } catch (RuntimeException exception) {
            LOG.warn("Falha no ciclo de publicacao da Outbox.", exception);
        }
    }

    private void consumeTick(String topic) {
        try {
            sqsConsumer.consumirDisponiveis(topic);
        } catch (RuntimeException exception) {
            LOG.warnf(exception, "Falha no worker da fila %s.", DomainMessagingRoutes.queueName(topic));
        }
    }
}
