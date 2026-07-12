package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxPublisherTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 7, 12, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void deveCalcularMultiplicadorDeRetryComLimiteInferior() {
        assertEquals(1L, OutboxPublisher.retryMultiplier(0));
        assertEquals(1L, OutboxPublisher.retryMultiplier(1));
    }

    @Test
    void deveCalcularMultiplicadorDeRetryComLimiteSuperior() {
        assertEquals(2L, OutboxPublisher.retryMultiplier(2));
        assertEquals(1024L, OutboxPublisher.retryMultiplier(20));
    }

    @Test
    void devePublicarEventoPendenteComAtributosContratados() {
        var store = new InMemoryBillingEventStore();
        store.registrarOutbox(
                "pagamento-1",
                "pagamentoSolicitado",
                "oficina.billing.pagamento-solicitado",
                Map.of("pagamentoId", "pagamento-1"),
                "corr-billing-publisher",
                NOW).join();
        var messagingClient = new RecordingMessagingClient();
        var publisher = publisher(store, messagingClient, 3);

        var publicados = publisher.publicarPendentes();

        assertEquals(1, publicados.size());
        assertEquals("PUBLISHED", store.listarOutbox().getFirst().status());
        assertEquals(1, messagingClient.messages.size());
        var message = messagingClient.messages.getFirst();
        assertEquals("oficina.billing.pagamento-solicitado", message.topic());
        assertEquals("pagamentoSolicitado", message.attributes().get("eventType"));
        assertEquals("oficina-billing-service", message.attributes().get("producer"));
        assertEquals("pagamento-1", message.attributes().get("aggregateId"));
        assertEquals("corr-billing-publisher", message.attributes().get("correlationId"));
        assertTrue(message.body().contains("pagamentoSolicitado"));
    }

    @Test
    void deveRegistrarFalhaFinalQuandoPublicacaoFalhar() {
        var store = new InMemoryBillingEventStore();
        store.registrarOutbox(
                "pagamento-1",
                "pagamentoSolicitado",
                "oficina.billing.pagamento-solicitado",
                Map.of("pagamentoId", "pagamento-1"),
                "corr-billing-failure",
                NOW).join();
        var messagingClient = new RecordingMessagingClient();
        messagingClient.failure = new IllegalStateException(
                "sns indisponivel",
                new IllegalArgumentException("endpoint sem resposta"));
        var publisher = publisher(store, messagingClient, 1);

        var publicados = publisher.publicarPendentes();

        assertTrue(publicados.isEmpty());
        var failure = store.listarOutbox().getFirst();
        assertEquals("FAILED", failure.status());
        assertEquals(1, failure.attempts());
        assertEquals("endpoint sem resposta", failure.lastError());
        assertTrue(messagingClient.messages.isEmpty());
    }

    private static OutboxPublisher publisher(
            BillingEventStore store,
            RecordingMessagingClient messagingClient,
            int maxAttempts) {
        return new OutboxPublisher(
                store,
                messagingClient,
                new DomainEventJsonCodec(new ObjectMapper().findAndRegisterModules()),
                true,
                10,
                maxAttempts,
                1);
    }

    private static final class RecordingMessagingClient extends AwsDomainMessagingClient {
        private final List<PublishedMessage> messages = new ArrayList<>();
        private RuntimeException failure;

        private RecordingMessagingClient() {
            super(
                    DomainMessagingRoutes.SERVICE_NAME,
                    "us-east-1",
                    "http://localhost:4566",
                    Optional.of("000000000000"),
                    Optional.of("test"),
                    Optional.of("test"));
        }

        @Override
        void publish(String topic, String message, Map<String, String> attributes) {
            if (failure != null) {
                throw failure;
            }
            messages.add(new PublishedMessage(topic, message, Map.copyOf(attributes)));
        }
    }

    private record PublishedMessage(String topic, String body, Map<String, String> attributes) {
    }
}
