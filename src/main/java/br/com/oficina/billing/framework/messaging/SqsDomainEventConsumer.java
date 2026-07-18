package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.framework.observability.StructuredLog;
import br.com.oficina.billing.framework.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

@ApplicationScoped
class SqsDomainEventConsumer {
    private static final Logger LOG = Logger.getLogger(SqsDomainEventConsumer.class);

    private final AwsDomainMessagingClient messagingClient;
    private final DomainEventJsonCodec codec;
    private final BillingEventConsumer consumer;
    private final OperationalMetrics metrics;
    private final boolean consumerEnabled;
    private final int maxMessages;
    private final int waitTimeSeconds;
    private final int maxReceiveCount;

    SqsDomainEventConsumer(
            AwsDomainMessagingClient messagingClient,
            DomainEventJsonCodec codec,
            BillingEventConsumer consumer,
            @ConfigProperty(name = "oficina.messaging.consumer.enabled", defaultValue = "false") boolean consumerEnabled,
            @ConfigProperty(name = "oficina.messaging.consumer.max-messages", defaultValue = "5") int maxMessages,
            @ConfigProperty(name = "oficina.messaging.consumer.wait-time-seconds", defaultValue = "10") int waitTimeSeconds) {
        this(
                messagingClient,
                codec,
                consumer,
                consumerEnabled,
                maxMessages,
                waitTimeSeconds,
                5,
                new OperationalMetrics(new SimpleMeterRegistry(), DomainMessagingRoutes.SERVICE_NAME));
    }

    @Inject
    SqsDomainEventConsumer(
            AwsDomainMessagingClient messagingClient,
            DomainEventJsonCodec codec,
            BillingEventConsumer consumer,
            @ConfigProperty(name = "oficina.messaging.consumer.enabled", defaultValue = "false") boolean consumerEnabled,
            @ConfigProperty(name = "oficina.messaging.consumer.max-messages", defaultValue = "5") int maxMessages,
            @ConfigProperty(name = "oficina.messaging.consumer.wait-time-seconds", defaultValue = "10") int waitTimeSeconds,
            @ConfigProperty(name = "oficina.messaging.consumer.max-receive-count", defaultValue = "5") int maxReceiveCount,
            OperationalMetrics metrics) {
        this.messagingClient = messagingClient;
        this.codec = codec;
        this.consumer = consumer;
        this.consumerEnabled = consumerEnabled;
        this.maxMessages = maxMessages;
        this.waitTimeSeconds = waitTimeSeconds;
        this.maxReceiveCount = maxReceiveCount;
        this.metrics = metrics;
    }

    int consumirDisponiveis() {
        if (!consumerEnabled) {
            return 0;
        }
        var consumed = 0;
        for (var topic : DomainMessagingRoutes.consumedTopics()) {
            consumed += consumirDisponiveis(topic);
        }
        return consumed;
    }

    int consumirDisponiveis(String topic) {
        if (!consumerEnabled) {
            return 0;
        }
        if (!DomainMessagingRoutes.consumedTopics().contains(topic)) {
            throw new IllegalArgumentException("Topico nao consumido pelo Billing: " + topic);
        }
        var consumed = 0;
        var queue = DomainMessagingRoutes.queueName(topic);
        var messages = messagingClient.receive(topic, maxMessages, waitTimeSeconds);
        for (var message : messages.messages()) {
                var startedAt = metrics.startSqsProcessing(queue, topic);
                var eventType = "unknown";
                try {
                    var event = codec.decode(message.body());
                    eventType = event.eventType();
                    consumer.consumir(event);
                    messagingClient.delete(messages.queueUrl(), message);
                    metrics.sqsConsumed(queue, topic, eventType, startedAt, event.occurredAt());
                    consumed++;
                    StructuredLog.info(LOG, "sqs domain event acknowledged", Map.of(
                            "eventId", event.eventId().toString(),
                            "eventType", event.eventType(),
                            "eventVersion", event.eventVersion(),
                            "producer", event.producer(),
                            "consumer", DomainMessagingRoutes.SERVICE_NAME,
                            "aggregateId", event.aggregateId(),
                            "queue", DomainMessagingRoutes.queueName(topic),
                            "messageStatus", "ACKED"));
                } catch (RuntimeException exception) {
                    metrics.sqsFailed(
                            queue,
                            topic,
                            eventType,
                            "unknown".equals(eventType) ? "decode_failure" : "processing_failure",
                            sentToDlq(message),
                            startedAt);
                    LOG.warnf(exception, "Falha ao processar mensagem SQS da fila %s", DomainMessagingRoutes.queueName(topic));
                }
        }
        return consumed;
    }

    private boolean sentToDlq(Message message) {
        var value = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (value == null) {
            return false;
        }
        try {
            return Integer.parseInt(value) >= maxReceiveCount;
        } catch (NumberFormatException _) {
            return false;
        }
    }
}
