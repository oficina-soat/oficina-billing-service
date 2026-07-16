package br.com.oficina.billing.framework.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class DomainEventJsonCodec {
    private final ObjectMapper objectMapper;

    DomainEventJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(OutboxEventRecord event) {
        try {
            return objectMapper.writeValueAsString(new DomainEventEnvelope(
                    event.eventId(),
                    event.eventType(),
                    event.eventVersion(),
                    event.occurredAt(),
                    event.producer(),
                    event.aggregateId(),
                    event.correlationId(),
                    event.payload()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Evento de Outbox nao pode ser serializado.", exception);
        }
    }

    DomainEventEnvelope decode(String messageBody) {
        try {
            return objectMapper.readValue(unwrapSnsEnvelope(messageBody), DomainEventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Mensagem SQS nao contem envelope de dominio valido.", exception);
        }
    }

    private String unwrapSnsEnvelope(String messageBody) throws JsonProcessingException {
        var root = objectMapper.readTree(messageBody);
        if (root.hasNonNull("Message")) {
            var message = root.get("Message");
            return message.isTextual() ? message.asText() : message.toString();
        }
        return messageBody;
    }
}
