package br.com.oficina.billing.core.usecases.pagamento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.AcaoPermitidaPagamento;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import br.com.oficina.billing.framework.db.InMemoryPagamentoDataSourceAdapter;
import br.com.oficina.billing.framework.messaging.InMemoryBillingEventStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReconciliarPagamentoUseCaseTest {
    @Test
    void deveConfirmarPelaConsultaAoProvedorUmaUnicaVez() {
        var repository = new InMemoryPagamentoDataSourceAdapter();
        var events = new InMemoryBillingEventStore();
        var payment = integratedPayment();
        repository.save(payment).join();
        var queries = new AtomicInteger();
        var gateway = new PagamentoGateway() {
            @Override
            public CompletableFuture<PagamentoGatewayResult> solicitar(Pagamento ignored) {
                return CompletableFuture.completedFuture(PagamentoGatewayResult.naoIntegrado());
            }

            @Override
            public CompletableFuture<PagamentoGatewayResult> consultar(Pagamento ignored) {
                queries.incrementAndGet();
                return CompletableFuture.completedFuture(
                        PagamentoGatewayResult.confirmado("mercado-pago", "mp-123"));
            }
        };
        var useCase = new ReconciliarPagamentoUseCase(repository, gateway, events);

        var confirmed = useCase.executar(new ReconciliarPagamentoUseCase.Command(payment.pagamentoId())).join();
        var replay = useCase.executarPorTransacaoExternaId("mp-123").join();

        assertEquals(StatusPagamento.CONFIRMADO, confirmed.status());
        assertEquals(StatusPagamento.CONFIRMADO, replay.status());
        assertEquals(1, queries.get());
        assertEquals(1, events.listarOutbox().stream()
                .filter(event -> "pagamentoConfirmado".equals(event.eventType()))
                .count());
    }

    @Test
    void deveOferecerSomenteReconciliacaoParaPagamentoIntegradoPendente() {
        var payment = integratedPayment();

        assertEquals(
                java.util.List.of(AcaoPermitidaPagamento.ATUALIZAR_STATUS),
                payment.acoesPermitidas());
        assertTrue(new Pagamento(
                        payment.pagamentoId(),
                        payment.ordemServicoId(),
                        payment.orcamentoId(),
                        payment.valor(),
                        payment.metodo(),
                        StatusPagamento.CONFIRMADO,
                        payment.provedor(),
                        payment.transacaoExternaId(),
                        payment.criadoEm(),
                        payment.atualizadoEm())
                .acoesPermitidas()
                .isEmpty());
    }

    @Test
    void deveConvergirReconciliacoesConcorrentesParaUmaOutbox() {
        var repository = new InMemoryPagamentoDataSourceAdapter();
        var events = new InMemoryBillingEventStore();
        var payment = integratedPayment();
        repository.save(payment).join();
        var responses = new ConcurrentLinkedQueue<CompletableFuture<PagamentoGatewayResult>>();
        PagamentoGateway gateway = new PagamentoGateway() {
            @Override
            public CompletableFuture<PagamentoGatewayResult> solicitar(Pagamento ignored) {
                return CompletableFuture.completedFuture(PagamentoGatewayResult.naoIntegrado());
            }

            @Override
            public CompletableFuture<PagamentoGatewayResult> consultar(Pagamento ignored) {
                var response = new CompletableFuture<PagamentoGatewayResult>();
                responses.add(response);
                return response;
            }
        };
        var useCase = new ReconciliarPagamentoUseCase(repository, gateway, events);

        var first = useCase.executar(new ReconciliarPagamentoUseCase.Command(payment.pagamentoId()));
        var second = useCase.executarPorTransacaoExternaId(payment.transacaoExternaId());
        assertEquals(2, responses.size());
        responses.forEach(response -> response.complete(
                PagamentoGatewayResult.confirmado("mercado-pago", payment.transacaoExternaId())));
        CompletableFuture.allOf(first, second).join();

        assertEquals(StatusPagamento.CONFIRMADO, repository.findById(payment.pagamentoId()).join().orElseThrow().status());
        assertEquals(1, events.listarOutbox().stream()
                .filter(event -> "pagamentoConfirmado".equals(event.eventType()))
                .count());
    }

    @Test
    void deveRecuperarGravacaoDaOutboxAposEstadoFinanceiroAtualizado() {
        var repository = new InMemoryPagamentoDataSourceAdapter();
        var events = new InMemoryBillingEventStore();
        var payment = integratedPayment();
        repository.save(payment).join();
        var queries = new AtomicInteger();
        PagamentoGateway gateway = new PagamentoGateway() {
            @Override
            public CompletableFuture<PagamentoGatewayResult> solicitar(Pagamento ignored) {
                return CompletableFuture.completedFuture(PagamentoGatewayResult.naoIntegrado());
            }

            @Override
            public CompletableFuture<PagamentoGatewayResult> consultar(Pagamento ignored) {
                queries.incrementAndGet();
                return CompletableFuture.completedFuture(
                        PagamentoGatewayResult.confirmado("mercado-pago", payment.transacaoExternaId()));
            }
        };
        var attempts = new AtomicInteger();
        OutboxEventSender flakyEvents = new OutboxEventSender() {
            @Override
            public CompletableFuture<Void> registrarOutbox(
                    String aggregateId,
                    String eventType,
                    String topic,
                    Map<String, Object> payload,
                    String correlationId,
                    OffsetDateTime occurredAt) {
                return events.registrarOutbox(aggregateId, eventType, topic, payload, correlationId, occurredAt);
            }

            @Override
            public CompletableFuture<Void> registrarOutboxIdempotente(
                    UUID eventId,
                    String aggregateId,
                    String eventType,
                    String topic,
                    Map<String, Object> payload,
                    String correlationId,
                    OffsetDateTime occurredAt) {
                if (attempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(new IllegalStateException("outbox indisponivel"));
                }
                return events.registrarOutboxIdempotente(
                        eventId, aggregateId, eventType, topic, payload, correlationId, occurredAt);
            }
        };
        var useCase = new ReconciliarPagamentoUseCase(repository, gateway, flakyEvents);

        assertThrows(
                CompletionException.class,
                () -> useCase.executar(new ReconciliarPagamentoUseCase.Command(payment.pagamentoId())).join());
        var recovered = useCase.executar(new ReconciliarPagamentoUseCase.Command(payment.pagamentoId())).join();

        assertEquals(StatusPagamento.CONFIRMADO, recovered.status());
        assertEquals(1, queries.get());
        assertEquals(1, events.listarOutbox().size());
    }

    private Pagamento integratedPayment() {
        var now = OffsetDateTime.parse("2026-01-01T12:00:00Z");
        return new Pagamento(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.TEN,
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                "mercado-pago",
                "mp-123",
                now,
                now);
    }
}
