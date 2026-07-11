package br.com.oficina.billing.core.usecases.pagamento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import br.com.oficina.billing.core.usecases.orcamento.AprovarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.GerarOrcamentoUseCase;
import br.com.oficina.billing.framework.db.InMemoryOrcamentoDataSourceAdapter;
import br.com.oficina.billing.framework.db.InMemoryPagamentoDataSourceAdapter;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PagamentoUseCaseTest {
    private final BillingEventStore eventStore = new BillingEventStore();
    private final InMemoryOrcamentoDataSourceAdapter orcamentoRepository = new InMemoryOrcamentoDataSourceAdapter();
    private final GerarOrcamentoUseCase gerarOrcamentoUseCase =
            new GerarOrcamentoUseCase(orcamentoRepository, eventStore, eventStore);
    private final AprovarOrcamentoUseCase aprovarOrcamentoUseCase =
            new AprovarOrcamentoUseCase(orcamentoRepository, eventStore);

    @Test
    void deveRegistrarPagamentoConfirmadoPeloGatewayEPublicarEventos() {
        var pagamentoRepository = new InMemoryPagamentoDataSourceAdapter();
        var registrarPagamentoUseCase = registrarPagamentoUseCase(
                pagamentoRepository,
                pagamento -> CompletableFuture.completedFuture(
                        PagamentoGatewayResult.confirmado("mercado-pago", "mp-confirmado-001")));
        var orcamento = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);

        var pagamento = registrarPagamentoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                orcamento.ordemServicoId(),
                orcamento.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();

        assertEquals(StatusPagamento.CONFIRMADO, pagamento.status());
        assertEquals("mercado-pago", pagamento.provedor());
        assertEquals("mp-confirmado-001", pagamento.transacaoExternaId());
        assertTrue(eventStore.listarOutbox().stream().anyMatch(event -> event.eventType().equals("pagamentoSolicitado")));
        assertTrue(eventStore.listarOutbox().stream().anyMatch(event -> event.eventType().equals("pagamentoConfirmado")));
    }

    @Test
    void deveRejeitarPagamentoQuandoOrcamentoNaoPertenceAOrdemOuNaoEstaAprovado() {
        var service = registrarPagamentoUseCase(new InMemoryPagamentoDataSourceAdapter(), gatewayNaoIntegrado());
        var orcamentoGerado = gerar(UUID.randomUUID());
        var ordemServicoId = orcamentoGerado.ordemServicoId();
        var orcamentoId = orcamentoGerado.orcamentoId();

        var erroEstado = assertFutureThrows(BusinessException.class, () ->
                service.executar(new RegistrarPagamentoUseCase.Command(
                        ordemServicoId,
                        orcamentoId,
                        BigDecimal.ZERO,
                        MetodoPagamento.PIX)));
        assertEquals("INVALID_STATE_TRANSITION", erroEstado.code());

        var orcamentoAprovado = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);
        var outraOrdemServicoId = UUID.randomUUID();
        var orcamentoAprovadoId = orcamentoAprovado.orcamentoId();
        var erroOwnership = assertFutureThrows(BusinessException.class, () ->
                service.executar(new RegistrarPagamentoUseCase.Command(
                        outraOrdemServicoId,
                        orcamentoAprovadoId,
                        BigDecimal.ZERO,
                        MetodoPagamento.PIX)));
        assertEquals("BUSINESS_RULE_VIOLATION", erroOwnership.code());
    }

    @Test
    void deveSolicitarPagamentoDaOrdemDeFormaIdempotentePorOrcamento() {
        var pagamentoRepository = new InMemoryPagamentoDataSourceAdapter();
        var registrarPagamentoUseCase = registrarPagamentoUseCase(pagamentoRepository, gatewayNaoIntegrado());
        var solicitarPagamentoDaOrdemUseCase = new SolicitarPagamentoDaOrdemUseCase(
                orcamentoRepository,
                pagamentoRepository,
                registrarPagamentoUseCase);
        var consultarPagamentosDaOrdemServicoUseCase =
                new ConsultarPagamentosDaOrdemServicoUseCase(pagamentoRepository);
        var orcamento = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);

        var primeiro = solicitarPagamentoDaOrdemUseCase.executar(
                new SolicitarPagamentoDaOrdemUseCase.Command(orcamento.ordemServicoId())).join();
        var segundo = solicitarPagamentoDaOrdemUseCase.executar(
                new SolicitarPagamentoDaOrdemUseCase.Command(orcamento.ordemServicoId())).join();

        assertEquals(primeiro.pagamentoId(), segundo.pagamentoId());
        assertEquals(1, consultarPagamentosDaOrdemServicoUseCase.executar(
                new ConsultarPagamentosDaOrdemServicoUseCase.Command(orcamento.ordemServicoId())).join().size());
    }

    @Test
    void deveFalharAoConsultarPagamentoInexistente() {
        var service = new ConsultarPagamentoUseCase(new InMemoryPagamentoDataSourceAdapter());
        var pagamentoId = UUID.randomUUID();

        assertFutureThrows(
                ResourceNotFoundException.class,
                () -> service.executar(new ConsultarPagamentoUseCase.Command(pagamentoId)));
    }

    private Orcamento gerar(UUID ordemServicoId) {
        return gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(ordemServicoId)).join();
    }

    private Orcamento aprovar(UUID orcamentoId, String motivo) {
        return aprovarOrcamentoUseCase.executar(new AprovarOrcamentoUseCase.Command(orcamentoId, motivo)).join();
    }

    private RegistrarPagamentoUseCase registrarPagamentoUseCase(
            InMemoryPagamentoDataSourceAdapter pagamentoRepository,
            PagamentoGateway pagamentoGateway) {
        return new RegistrarPagamentoUseCase(
                pagamentoRepository,
                orcamentoRepository,
                eventStore,
                pagamentoGateway);
    }

    private PagamentoGateway gatewayNaoIntegrado() {
        return pagamento -> CompletableFuture.completedFuture(PagamentoGatewayResult.naoIntegrado());
    }

    private <T extends Throwable> T assertFutureThrows(
            Class<T> expectedType,
            Supplier<CompletableFuture<Pagamento>> executable) {
        var exception = assertThrows(CompletionException.class, () -> executable.get().join());
        return assertInstanceOf(expectedType, exception.getCause());
    }
}
