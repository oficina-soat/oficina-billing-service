package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.framework.observability.StructuredLog;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
class SqsDomainEventConsumer {
    private static final Logger LOG = Logger.getLogger(SqsDomainEventConsumer.class);

    private final AwsDomainMessagingClient messagingClient;
    private final DomainEventJsonCodec codec;
    private final BillingEventConsumer consumer;
    private final boolean consumerEnabled;
    private final int maxMessages;
    private final int waitTimeSeconds;

    SqsDomainEventConsumer(
            AwsDomainMessagingClient messagingClient,
            DomainEventJsonCodec codec,
            BillingEventConsumer consumer,
            @ConfigProperty(name = "oficina.messaging.consumer.enabled", defaultValue = "false") boolean consumerEnabled,
            @ConfigProperty(name = "oficina.messaging.consumer.max-messages", defaultValue = "5") int maxMessages,
            @ConfigProperty(name = "oficina.messaging.consumer.wait-time-seconds", defaultValue = "10") int waitTimeSeconds) {
        this.messagingClient = messagingClient;
        this.codec = codec;
        this.consumer = consumer;
        this.consumerEnabled = consumerEnabled;
        this.maxMessages = maxMessages;
        this.waitTimeSeconds = waitTimeSeconds;
    }

    int consumirDisponiveis() {
        if (!consumerEnabled) {
            return 0;
        }
        var consumed = 0;
        for (var topic : DomainMessagingRoutes.consumedTopics()) {
            var messages = messagingClient.receive(topic, maxMessages, waitTimeSeconds);
            for (var message : messages.messages()) {
                try {
                    var event = codec.decode(message.body());
                    consumer.consumir(event);
                    messagingClient.delete(messages.queueUrl(), message);
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
                    LOG.warnf(exception, "Falha ao processar mensagem SQS da fila %s", DomainMessagingRoutes.queueName(topic));
                }
            }
        }
        return consumed;
    }
}
