package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import br.com.oficina.billing.core.usecases.orcamento.AprovarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.GerarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.CancelarPagamentosCriadosDaOrdemUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConfirmarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConsultarPagamentosDaOrdemServicoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.RegistrarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.SolicitarPagamentoDaOrdemUseCase;
import br.com.oficina.billing.framework.db.InMemoryOrcamentoDataSourceAdapter;
import br.com.oficina.billing.framework.db.InMemoryPagamentoDataSourceAdapter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BillingEventConsumerTest {
    private final BillingEventStore store = new InMemoryBillingEventStore();
    private final InMemoryOrcamentoDataSourceAdapter orcamentoRepository = new InMemoryOrcamentoDataSourceAdapter();
    private final InMemoryPagamentoDataSourceAdapter pagamentoRepository = new InMemoryPagamentoDataSourceAdapter();
    private final GerarOrcamentoUseCase gerarOrcamentoUseCase =
            new GerarOrcamentoUseCase(orcamentoRepository, store, store);
    private final AprovarOrcamentoUseCase aprovarOrcamentoUseCase =
            new AprovarOrcamentoUseCase(orcamentoRepository, store);
    private final RegistrarPagamentoUseCase registrarPagamentoUseCase = new RegistrarPagamentoUseCase(
            pagamentoRepository,
            orcamentoRepository,
            store,
            pagamento -> java.util.concurrent.CompletableFuture.completedFuture(PagamentoGatewayResult.naoIntegrado()));
    private final ConsultarPagamentosDaOrdemServicoUseCase consultarPagamentosDaOrdemServicoUseCase =
            new ConsultarPagamentosDaOrdemServicoUseCase(pagamentoRepository);
    private final ConfirmarPagamentoUseCase confirmarPagamentoUseCase =
            new ConfirmarPagamentoUseCase(pagamentoRepository, store);
    private final BillingEventConsumer consumer = new BillingEventConsumer(
            store,
            gerarOrcamentoUseCase,
            new SolicitarPagamentoDaOrdemUseCase(orcamentoRepository, registrarPagamentoUseCase),
            new CancelarPagamentosCriadosDaOrdemUseCase(pagamentoRepository));
    private final OutboxPublisher publisher = new OutboxPublisher(store);

    @Test
    void deveProjetarContatoRecebidoNaCriacaoDaOrdem() {
        var ordemServicoId = UUID.randomUUID();
        var evento = envelope(
                UUID.randomUUID(),
                "ordemDeServicoCriada",
                ordemServicoId,
                Map.of("ordemServicoId", ordemServicoId.toString(), "clienteEmail", "cliente@oficina.test"));

        assertTrue(consumer.consumir(evento));
        assertEquals("cliente@oficina.test", store.buscarContato(ordemServicoId).orElseThrow());
    }

    @Test
    void deveAceitarCriacaoSemContatoEUsarAggregateIdComoFallback() {
        var ordemServicoId = UUID.randomUUID();
        var evento = envelope(UUID.randomUUID(), "ordemDeServicoCriada", ordemServicoId, Map.of());

        assertTrue(consumer.consumir(evento));
        assertTrue(store.buscarContato(ordemServicoId).isEmpty());
    }

    @Test
    void deveProjetarPecaEServicoRecebidosIndividualmente() {
        var ordemServicoId = UUID.randomUUID();
        var pecaId = UUID.randomUUID();
        var servicoId = UUID.randomUUID();

        assertTrue(consumer.consumir(envelope(UUID.randomUUID(), "pecaIncluidaNaOrdemDeServico", ordemServicoId,
                Map.of("ordemServicoId", ordemServicoId, "peca", item("pecaId", pecaId, "Filtro")))));
        assertTrue(consumer.consumir(envelope(UUID.randomUUID(), "servicoIncluidoNaOrdemDeServico", ordemServicoId,
                Map.of("ordemServicoId", ordemServicoId, "servico", item("servicoId", servicoId, "Troca")))));

        var orcamento = gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(ordemServicoId)).join();
        assertEquals(2, orcamento.itens().size());
    }

    @Test
    void deveGerarOrcamentoComSnapshotFinanceiroEPublicarEventos() {
        var ordemServicoId = UUID.randomUUID();
        var eventoDiagnostico = envelope(
                UUID.randomUUID(),
                "diagnosticoFinalizado",
                ordemServicoId,
                Map.of(
                        "ordemServicoId", ordemServicoId.toString(),
                        "pecas", List.of(Map.of(
                                "pecaId", UUID.randomUUID().toString(),
                                "nome", "Bateria 60Ah",
                                "quantidade", "1.0",
                                "valorUnitario", "320.00",
                                "valorTotal", "320.00")),
                        "servicos", List.of(Map.of(
                                "servicoId", UUID.randomUUID().toString(),
                                "nome", "Troca de bateria",
                                "quantidade", "1.0",
                                "valorUnitario", "80.00",
                                "valorTotal", "80.00"))));

        assertTrue(consumer.consumir(eventoDiagnostico));
        assertFalse(consumer.consumir(eventoDiagnostico));

        var orcamento = orcamentoRepository.findByOrdemServicoId(ordemServicoId).join().getFirst();

        assertEquals(new BigDecimal("400.00"), orcamento.valorTotal());
        assertEquals(2, orcamento.itens().size());
        assertTrue(store.listarOutbox().stream().anyMatch(event ->
                event.eventType().equals("orcamentoGerado")
                        && event.topic().equals("oficina.billing.orcamento-gerado")
                        && event.payload().get("valorTotal").equals(new BigDecimal("400.00"))));
    }

    @Test
    void deveReutilizarOrcamentoEOutboxAoRetentarNotificacao() {
        var ordemServicoId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var notificationAttempts = new AtomicInteger();
        var useCase = new GerarOrcamentoUseCase(
                orcamentoRepository,
                store,
                store,
                ignored -> notificationAttempts.getAndIncrement() == 0
                        ? java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("notificacao indisponivel"))
                        : java.util.concurrent.CompletableFuture.completedFuture(null));
        var retryingConsumer = new BillingEventConsumer(
                store,
                useCase,
                new SolicitarPagamentoDaOrdemUseCase(
                        orcamentoRepository, registrarPagamentoUseCase),
                new CancelarPagamentosCriadosDaOrdemUseCase(pagamentoRepository));
        var evento = envelope(eventId, "diagnosticoFinalizado", ordemServicoId, Map.of(
                "ordemServicoId", ordemServicoId.toString(),
                "pecas", List.of(),
                "servicos", List.of()));

        assertThrows(CompletionException.class, () -> retryingConsumer.consumir(evento));
        assertEquals(1, orcamentoRepository.findByOrdemServicoId(ordemServicoId).join().size());
        assertEquals(1, store.listarOutbox().stream()
                .filter(outbox -> outbox.eventType().equals("orcamentoGerado"))
                .count());

        assertTrue(retryingConsumer.consumir(evento));
        assertFalse(retryingConsumer.consumir(evento));

        assertEquals(2, notificationAttempts.get());
        assertEquals(1, orcamentoRepository.findByOrdemServicoId(ordemServicoId).join().size());
        assertEquals(1, store.listarOutbox().stream()
                .filter(outbox -> outbox.eventType().equals("orcamentoGerado"))
                .count());
    }

    @Test
    void naoDeveRegredirNemRenotificarOrcamentoJaAprovadoDuranteRetentativa() {
        var ordemServicoId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var notificationAttempts = new AtomicInteger();
        var useCase = new GerarOrcamentoUseCase(
                orcamentoRepository,
                store,
                store,
                ignored -> {
                    notificationAttempts.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("notificacao indisponivel"));
                });
        var retryingConsumer = new BillingEventConsumer(
                store,
                useCase,
                new SolicitarPagamentoDaOrdemUseCase(
                        orcamentoRepository, registrarPagamentoUseCase),
                new CancelarPagamentosCriadosDaOrdemUseCase(pagamentoRepository));
        var evento = envelope(eventId, "diagnosticoFinalizado", ordemServicoId, Map.of(
                "ordemServicoId", ordemServicoId.toString(),
                "pecas", List.of(),
                "servicos", List.of()));

        assertThrows(CompletionException.class, () -> retryingConsumer.consumir(evento));
        var orcamento = orcamentoRepository.findByOrdemServicoId(ordemServicoId).join().getFirst();
        aprovarOrcamentoUseCase.executar(
                new AprovarOrcamentoUseCase.Command(orcamento.orcamentoId(), "Aprovado antes da retentativa")).join();

        assertTrue(retryingConsumer.consumir(evento));

        var preservado = orcamentoRepository.findById(orcamento.orcamentoId()).join().orElseThrow();
        assertEquals(StatusOrcamento.APROVADO, preservado.status());
        assertEquals(1, notificationAttempts.get());
        assertEquals(1, orcamentoRepository.findByOrdemServicoId(ordemServicoId).join().size());
        assertEquals(1, store.listarOutbox().stream()
                .filter(outbox -> outbox.eventType().equals("orcamentoGerado"))
                .count());
    }

    @Test
    void deveSolicitarPagamentoQuandoOrdemForFinalizada() {
        var ordemServicoId = UUID.randomUUID();
        var orcamento = gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(ordemServicoId)).join();
        var aprovado = aprovarOrcamentoUseCase.executar(
                new AprovarOrcamentoUseCase.Command(orcamento.orcamentoId(), "Aprovado pelo cliente")).join();

        assertEquals(StatusOrcamento.APROVADO, aprovado.status());

        var eventoFinalizacao = envelope(
                UUID.randomUUID(),
                "ordemDeServicoFinalizada",
                ordemServicoId,
                Map.of("ordemServicoId", ordemServicoId.toString()));

        assertTrue(consumer.consumir(eventoFinalizacao));
        assertFalse(consumer.consumir(eventoFinalizacao));

        var pagamentos = consultarPagamentosDaOrdemServicoUseCase.executar(
                new ConsultarPagamentosDaOrdemServicoUseCase.Command(ordemServicoId)).join();
        assertEquals(1, pagamentos.size());
        assertEquals(StatusPagamento.CRIADO, pagamentos.getFirst().status());
        assertEquals(MetodoPagamento.PIX, pagamentos.getFirst().metodo());
        assertTrue(store.listarOutbox().stream().anyMatch(event ->
                event.eventType().equals("pagamentoSolicitado")
                        && event.topic().equals("oficina.billing.pagamento-solicitado")));
    }

    @Test
    void deveCriarUmPagamentoEUmaOutboxComEventosDeFinalizacaoConcorrentes() {
        var ordemServicoId = UUID.randomUUID();
        var orcamento = gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(ordemServicoId)).join();
        aprovarOrcamentoUseCase.executar(
                new AprovarOrcamentoUseCase.Command(orcamento.orcamentoId(), "Aprovado pelo cliente")).join();
        var barrier = new CyclicBarrier(2);
        var gatewayCalls = new AtomicInteger();
        var paymentIds = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
        var concurrentRegistrar = new RegistrarPagamentoUseCase(
                pagamentoRepository,
                orcamentoRepository,
                store,
                pagamento -> {
                    gatewayCalls.incrementAndGet();
                    paymentIds.add(pagamento.pagamentoId());
                    await(barrier);
                    return CompletableFuture.completedFuture(PagamentoGatewayResult.naoIntegrado());
                });
        var concurrentConsumer = new BillingEventConsumer(
                store,
                gerarOrcamentoUseCase,
                new SolicitarPagamentoDaOrdemUseCase(orcamentoRepository, concurrentRegistrar),
                new CancelarPagamentosCriadosDaOrdemUseCase(pagamentoRepository));
        var execucaoFinalizada = envelope(
                UUID.randomUUID(),
                "execucaoFinalizada",
                ordemServicoId,
                Map.of("ordemServicoId", ordemServicoId.toString()));
        var ordemFinalizada = envelope(
                UUID.randomUUID(),
                "ordemDeServicoFinalizada",
                ordemServicoId,
                Map.of("ordemServicoId", ordemServicoId.toString()));

        var consumos = CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> assertTrue(concurrentConsumer.consumir(execucaoFinalizada))),
                CompletableFuture.runAsync(() -> assertTrue(concurrentConsumer.consumir(ordemFinalizada))));
        consumos.orTimeout(10, TimeUnit.SECONDS).join();

        assertEquals(2, gatewayCalls.get());
        assertEquals(1, paymentIds.size());
        assertEquals(1, pagamentoRepository.findByOrdemServicoId(ordemServicoId).join().size());
        assertEquals(1, store.listarOutbox().stream()
                .filter(event -> event.eventType().equals("pagamentoSolicitado")
                        && event.payload().get("ordemServicoId").equals(ordemServicoId.toString()))
                .count());
    }

    @Test
    void devePublicarEventosPendentesDaOutbox() {
        var ordemServicoId = UUID.randomUUID();
        var orcamento = gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(ordemServicoId)).join();
        aprovarOrcamentoUseCase.executar(new AprovarOrcamentoUseCase.Command(orcamento.orcamentoId(), null)).join();
        var pagamento = registrarPagamentoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                ordemServicoId,
                orcamento.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();
        confirmarPagamentoUseCase.executar(
                new ConfirmarPagamentoUseCase.Command(pagamento.pagamentoId(), "mercado-pago", "mp-test")).join();

        var publicados = publisher.publicarPendentes();

        assertEquals(4, publicados.size());
        assertTrue(store.listarOutbox().stream().allMatch(event -> event.status().equals("PUBLISHED")));
        assertTrue(publicados.stream().anyMatch(event -> event.eventType().equals("pagamentoConfirmado")));
    }

    @Test
    void deveRejeitarEventoNaoContratadoParaConsumo() {
        var envelope = envelope(UUID.randomUUID(), "eventoInexistente", UUID.randomUUID(), Map.of());

        assertThrows(IllegalArgumentException.class, () -> consumer.consumir(envelope));
    }

    @Test
    void deveRegistrarEventosSomenteParaAuditoriaECancelarPagamentosNaCompensacao() {
        var ordemServicoId = UUID.randomUUID();
        for (var eventType : List.of(
                "ordemDeServicoEntregue", "estoqueAcrescentado", "estoqueBaixado", "sagaFinalizadaComSucesso")) {
            assertTrue(consumer.consumir(envelope(UUID.randomUUID(), eventType, ordemServicoId, Map.of())));
        }
        assertTrue(consumer.consumir(envelope(
                UUID.randomUUID(), "sagaCompensada", ordemServicoId, Map.of("ordemServicoId", ordemServicoId))));
    }

    private Map<String, Object> item(String idField, UUID id, String nome) {
        return Map.of(
                idField, id,
                "nome", nome,
                "quantidade", BigDecimal.ONE,
                "valorUnitario", new BigDecimal("10.00"),
                "valorTotal", new BigDecimal("10.00"));
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Consumidores nao alcancaram a barreira concorrente.", exception);
        }
    }

    private DomainEventEnvelope envelope(UUID eventId, String eventType, UUID ordemServicoId, Map<String, Object> payload) {
        return new DomainEventEnvelope(
                eventId,
                eventType,
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                "oficina-os-service",
                ordemServicoId.toString(),
                payload);
    }
}
