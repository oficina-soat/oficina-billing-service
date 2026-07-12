package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@QuarkusTest
@QuarkusTestResource(LocalStackMessagingTestResource.class)
class SnsSqsMessagingIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Inject
    BillingEventStore store;

    @Inject
    OutboxPublisher outboxPublisher;

    @Inject
    SqsDomainEventConsumer sqsConsumer;

    @Test
    void devePublicarOutboxNoSnsEEntregarNaFilaConsumidora() throws Exception {
        var ordemServicoId = UUID.randomUUID();
        store.registrarOutbox(
                ordemServicoId.toString(),
                "pagamentoConfirmado",
                "oficina.billing.pagamento-confirmado",
                Map.of("ordemServicoId", ordemServicoId.toString(), "pagamentoId", UUID.randomUUID().toString()),
                "corr-billing-publish",
                OffsetDateTime.now(ZoneOffset.UTC)).join();

        var publicados = outboxPublisher.publicarPendentes();

        assertEquals(1, publicados.size());
        assertEquals("PUBLISHED", store.listarOutbox().getFirst().status());

        try (var sqs = LocalStackMessagingTestResource.sqsClient()) {
            var queueUrl = queueUrl(sqs, "oficina.billing.pagamento-confirmado", "oficina-os-service");
            var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(2)
                    .build()).messages();
            assertEquals(1, messages.size());
            assertEquals("pagamentoConfirmado", JSON.readTree(messages.getFirst().body()).path("eventType").asText());
        }
    }

    @Test
    void deveConsumirSqsEAckSomenteAposPersistirEvento() throws Exception {
        var ordemServicoId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var evento = new DomainEventEnvelope(
                eventId,
                "pecaIncluidaNaOrdemDeServico",
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                "oficina-os-service",
                ordemServicoId.toString(),
                Map.of(
                        "ordemServicoId", ordemServicoId.toString(),
                        "peca", Map.of(
                                "pecaId", UUID.randomUUID().toString(),
                                "nome", "Filtro de oleo",
                                "quantidade", BigDecimal.ONE,
                                "valorUnitario", new BigDecimal("45.00"),
                                "valorTotal", new BigDecimal("45.00"))));

        var message = JSON.writeValueAsString(evento);
        try (var sns = LocalStackMessagingTestResource.snsClient()) {
            sns.publish(builder -> builder
                    .topicArn(LocalStackMessagingTestResource.topicArn("oficina.os.peca-incluida-na-ordem-de-servico"))
                    .message(message));
        }

        assertEquals(1, sqsConsumer.consumirDisponiveis());
        assertTrue(store.eventoConsumido(eventId));

        try (var sqs = LocalStackMessagingTestResource.sqsClient()) {
            var queueUrl = queueUrl(sqs, "oficina.os.peca-incluida-na-ordem-de-servico", "oficina-billing-service");
            var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(1)
                    .build()).messages();
            assertTrue(messages.isEmpty());
        }
    }

    private static String queueUrl(software.amazon.awssdk.services.sqs.SqsClient sqs, String topic, String consumer) {
        return sqs.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(LocalStackMessagingTestResource.queueName(topic, consumer))
                .build()).queueUrl();
    }
}
