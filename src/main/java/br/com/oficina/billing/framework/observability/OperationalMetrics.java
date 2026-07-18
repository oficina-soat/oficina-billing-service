package br.com.oficina.billing.framework.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OperationalMetrics {
    private static final String TAG_SERVICE = "service";
    private static final String TAG_REASON = "reason";
    private static final String TAG_QUEUE = "queue";
    private static final String TAG_TOPIC = "topic";
    private static final String TAG_OPERATION = "operation";
    private static final String TAG_EVENT_TYPE = "eventType";
    private final MeterRegistry registry;
    private final String service;
    private final Map<String, AtomicLong> pendingByEventType = new ConcurrentHashMap<>();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public OperationalMetrics(
            MeterRegistry registry,
            @ConfigProperty(name = "quarkus.application.name") String service) {
        this.registry = registry;
        this.service = service;
        Gauge.builder("outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .tags(TAG_SERVICE, service)
                .description("Idade em segundos do evento pendente mais antigo da Outbox")
                .register(registry);
    }

    public <T> T persistence(String database, String resource, String operation, Supplier<T> action) {
        var startedAt = System.nanoTime();
        try {
            var result = action.get();
            recordPersistence(database, resource, operation, "success", "none", startedAt);
            return result;
        } catch (RuntimeException exception) {
            recordPersistence(database, resource, operation, "failure", categorize(exception), startedAt);
            throw exception;
        }
    }

    public void persistence(String database, String resource, String operation, Runnable action) {
        persistence(database, resource, operation, () -> {
            action.run();
            return null;
        });
    }

    public void observeOutbox(Map<String, Long> pending, OffsetDateTime oldestCreatedAt) {
        pendingByEventType.values().forEach(value -> value.set(0));
        pending.forEach((eventType, count) -> pendingGauge(eventType).set(count));
        oldestPendingAgeSeconds.set(oldestCreatedAt == null
                ? 0
                : Math.max(0, Duration.between(oldestCreatedAt, OffsetDateTime.now(ZoneOffset.UTC)).toSeconds()));
    }

    public long startOutboxAttempt(String eventType, String topic) {
        registry.counter("outbox.publish.attempts.count", outboxTags(eventType, topic)).increment();
        return System.nanoTime();
    }

    public void outboxPublished(String eventType, String topic, long startedAt) {
        outboxPublished(eventType, topic, startedAt, null);
    }

    public void outboxPublished(String eventType, String topic, long startedAt, OffsetDateTime createdAt) {
        registry.counter("outbox.published.count", outboxTags(eventType, topic)).increment();
        registry.counter("messaging.events.published.count", messagingTags(eventType, topic)).increment();
        registry.timer("outbox.publish.latency", outboxTags(eventType, topic))
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        if (createdAt != null) {
            registry.timer("outbox.pending.duration", outboxTags(eventType, topic))
                    .record(nonNegativeDuration(createdAt));
        }
    }

    public void outboxFailed(String eventType, String topic, String reason) {
        registry.counter("outbox.failed.count", outboxTags(eventType, topic)).increment();
        registry.counter(
                        "messaging.events.failed.count",
                        messagingTags(eventType, topic).and(TAG_REASON, reason))
                .increment();
    }

    public long startSqsProcessing(String queue, String topic) {
        registry.counter("messaging.sqs.received.count", TAG_SERVICE, service, TAG_QUEUE, queue, TAG_TOPIC, topic)
                .increment();
        return System.nanoTime();
    }

    public void sqsConsumed(String queue, String topic, String eventType, long startedAt) {
        sqsConsumed(queue, topic, eventType, startedAt, null);
    }

    public void sqsConsumed(
            String queue, String topic, String eventType, long startedAt, OffsetDateTime occurredAt) {
        var tags = messagingTags(eventType, topic).and(TAG_QUEUE, queue);
        registry.counter("messaging.events.consumed.count", tags).increment();
        registry.timer("messaging.events.processing.duration", tags)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        if (occurredAt != null) {
            registry.timer("messaging.events.delivery.latency", tags).record(nonNegativeDuration(occurredAt));
        }
    }

    public void sqsFailed(String queue, String topic, String eventType, String reason, boolean sentToDlq, long startedAt) {
        var tags = messagingTags(eventType, topic).and(TAG_QUEUE, queue);
        registry.counter("messaging.events.failed.count", tags.and(TAG_REASON, reason)).increment();
        registry.timer("messaging.events.processing.duration", tags)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        if (sentToDlq) {
            registry.counter("messaging.dlq.count", tags).increment();
        }
    }

    public void idempotencyRetry(String operation, String status) {
        registry.counter("idempotency.retries.count", TAG_SERVICE, service, TAG_OPERATION, operation, "status", status)
                .increment();
    }

    public void idempotencyConflict(String operation, String reason) {
        registry.counter("idempotency.conflicts.count", TAG_SERVICE, service, TAG_OPERATION, operation, TAG_REASON, reason)
                .increment();
    }

    private void recordPersistence(
            String database, String resource, String operation, String result, String error, long startedAt) {
        var tags = Tags.of(
                TAG_SERVICE, service,
                "database", database,
                "resource", resource,
                TAG_OPERATION, operation,
                "result", result,
                "error", error);
        registry.counter("persistence.operations.count", tags).increment();
        registry.timer("persistence.operation.duration", tags)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private AtomicLong pendingGauge(String eventType) {
        return pendingByEventType.computeIfAbsent(eventType, key -> {
            var value = new AtomicLong();
            Gauge.builder("outbox.pending.count", value, AtomicLong::doubleValue)
                    .tags(TAG_SERVICE, service, TAG_EVENT_TYPE, key)
                    .description("Quantidade de eventos pendentes na Outbox")
                    .register(registry);
            return value;
        });
    }

    private Tags outboxTags(String eventType, String topic) {
        return Tags.of(TAG_SERVICE, service, TAG_EVENT_TYPE, eventType, TAG_TOPIC, topic);
    }

    private Tags messagingTags(String eventType, String topic) {
        return Tags.of(TAG_SERVICE, service, TAG_EVENT_TYPE, eventType, TAG_TOPIC, topic);
    }

    private String categorize(RuntimeException exception) {
        var name = exception.getClass().getSimpleName().toLowerCase();
        if (name.contains("conflict") || name.contains("constraint") || name.contains("duplicate")) {
            return "conflict";
        }
        if (name.contains("notfound") || name.contains("not_found")) {
            return "not_found";
        }
        if (name.contains("timeout")) {
            return "timeout";
        }
        if (name.contains("connect") || name.contains("unavailable")) {
            return "unavailable";
        }
        return "other";
    }

    private static Duration nonNegativeDuration(OffsetDateTime since) {
        var duration = Duration.between(since, OffsetDateTime.now(ZoneOffset.UTC));
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
