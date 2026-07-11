package br.com.oficina.billing.core.usecases.pagamento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
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
    void deveAplicarResultadosCriadoERecusadoRetornadosPeloGateway() {
        var pagamentoCriadoRepository = new InMemoryPagamentoDataSourceAdapter();
        var registrarPagamentoCriadoUseCase = registrarPagamentoUseCase(
                pagamentoCriadoRepository,
                pagamento -> CompletableFuture.completedFuture(
                        PagamentoGatewayResult.criado("mercado-pago", "mp-criado-001")));
        var orcamentoCriado = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);

        var pagamentoCriado = registrarPagamentoCriadoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                orcamentoCriado.ordemServicoId(),
                orcamentoCriado.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();

        assertEquals(StatusPagamento.CRIADO, pagamentoCriado.status());
        assertEquals("mercado-pago", pagamentoCriado.provedor());
        assertEquals("mp-criado-001", pagamentoCriado.transacaoExternaId());

        var pagamentoRecusadoRepository = new InMemoryPagamentoDataSourceAdapter();
        var registrarPagamentoRecusadoUseCase = registrarPagamentoUseCase(
                pagamentoRecusadoRepository,
                pagamento -> CompletableFuture.completedFuture(PagamentoGatewayResult.recusado(
                        "mercado-pago",
                        "mp-recusado-001",
                        "Pagamento recusado pelo provedor")));
        var orcamentoRecusado = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);

        var pagamentoRecusado = registrarPagamentoRecusadoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                orcamentoRecusado.ordemServicoId(),
                orcamentoRecusado.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();

        assertEquals(StatusPagamento.RECUSADO, pagamentoRecusado.status());
        assertEquals("mercado-pago", pagamentoRecusado.provedor());
        assertEquals("mp-recusado-001", pagamentoRecusado.transacaoExternaId());
        assertTrue(eventStore.listarOutbox().stream().anyMatch(event ->
                event.eventType().equals("pagamentoRecusado")
                        && event.topic().equals("oficina.billing.pagamento-recusado")
                        && event.payload().get("motivo").equals("Pagamento recusado pelo provedor")));
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
    void deveRejeitarPagamentoComValorNegativoOuOrcamentoInexistente() {
        var service = registrarPagamentoUseCase(new InMemoryPagamentoDataSourceAdapter(), gatewayNaoIntegrado());
        var orcamentoAprovado = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);

        var erroValor = assertFutureThrows(BusinessException.class, () ->
                service.executar(new RegistrarPagamentoUseCase.Command(
                        orcamentoAprovado.ordemServicoId(),
                        orcamentoAprovado.orcamentoId(),
                        BigDecimal.valueOf(-1),
                        MetodoPagamento.PIX)));
        assertEquals("VALIDATION_ERROR", erroValor.code());

        assertFutureThrows(ResourceNotFoundException.class, () ->
                service.executar(new RegistrarPagamentoUseCase.Command(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        BigDecimal.ZERO,
                        MetodoPagamento.PIX)));
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
    void deveRecusarPagamentoCriadoEPublicarEvento() {
        var pagamentoRepository = new InMemoryPagamentoDataSourceAdapter();
        var registrarPagamentoUseCase = registrarPagamentoUseCase(pagamentoRepository, gatewayNaoIntegrado());
        var recusarPagamentoUseCase = new RecusarPagamentoUseCase(pagamentoRepository, eventStore);
        var orcamento = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);
        var pagamento = registrarPagamentoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                orcamento.ordemServicoId(),
                orcamento.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();

        var recusado = recusarPagamentoUseCase.executar(new RecusarPagamentoUseCase.Command(
                pagamento.pagamentoId(),
                "mercado-pago",
                "Cartao recusado")).join();

        assertEquals(StatusPagamento.RECUSADO, recusado.status());
        assertEquals("mercado-pago", recusado.provedor());
        assertTrue(eventStore.listarOutbox().stream().anyMatch(event ->
                event.eventType().equals("pagamentoRecusado")
                        && event.payload().get("motivo").equals("Cartao recusado")));

        var erroEstado = assertFutureThrows(BusinessException.class, () ->
                recusarPagamentoUseCase.executar(new RecusarPagamentoUseCase.Command(
                        recusado.pagamentoId(),
                        "mercado-pago",
                        "Nova tentativa")));
        assertEquals("INVALID_STATE_TRANSITION", erroEstado.code());
    }

    @Test
    void deveFalharAoRecusarPagamentoInexistente() {
        var service = new RecusarPagamentoUseCase(new InMemoryPagamentoDataSourceAdapter(), eventStore);

        assertFutureThrows(ResourceNotFoundException.class, () ->
                service.executar(new RecusarPagamentoUseCase.Command(
                        UUID.randomUUID(),
                        "mercado-pago",
                        "Pagamento inexistente")));
    }

    @Test
    void deveCancelarPagamentoCriadoERejeitarEstadoInvalido() {
        var pagamentoRepository = new InMemoryPagamentoDataSourceAdapter();
        var registrarPagamentoUseCase = registrarPagamentoUseCase(pagamentoRepository, gatewayNaoIntegrado());
        var cancelarPagamentoUseCase = new CancelarPagamentoUseCase(pagamentoRepository);
        var orcamento = aprovar(gerar(UUID.randomUUID()).orcamentoId(), null);
        var pagamento = registrarPagamentoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                orcamento.ordemServicoId(),
                orcamento.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();

        var cancelado = cancelarPagamentoUseCase.executar(
                new CancelarPagamentoUseCase.Command(pagamento.pagamentoId())).join();

        assertEquals(StatusPagamento.CANCELADO, cancelado.status());

        var erroEstado = assertFutureThrows(BusinessException.class, () ->
                cancelarPagamentoUseCase.executar(new CancelarPagamentoUseCase.Command(cancelado.pagamentoId())));
        assertEquals("INVALID_STATE_TRANSITION", erroEstado.code());
    }

    @Test
    void deveFalharAoCancelarPagamentoInexistente() {
        var service = new CancelarPagamentoUseCase(new InMemoryPagamentoDataSourceAdapter());

        assertFutureThrows(
                ResourceNotFoundException.class,
                () -> service.executar(new CancelarPagamentoUseCase.Command(UUID.randomUUID())));
    }

    @Test
    void deveCancelarSomentePagamentosCriadosDaOrdem() {
        var pagamentoRepository = new InMemoryPagamentoDataSourceAdapter();
        var registrarPagamentoUseCase = registrarPagamentoUseCase(pagamentoRepository, gatewayNaoIntegrado());
        var confirmarPagamentoUseCase = new ConfirmarPagamentoUseCase(pagamentoRepository, eventStore);
        var cancelarPagamentosCriadosUseCase = new CancelarPagamentosCriadosDaOrdemUseCase(pagamentoRepository);
        var ordemServicoId = UUID.randomUUID();
        var primeiroOrcamento = aprovar(gerar(ordemServicoId).orcamentoId(), null);
        var segundoOrcamento = aprovar(gerar(ordemServicoId).orcamentoId(), null);
        var pagamentoCriado = registrarPagamentoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                ordemServicoId,
                primeiroOrcamento.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();
        var pagamentoConfirmado = registrarPagamentoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                ordemServicoId,
                segundoOrcamento.orcamentoId(),
                BigDecimal.ZERO,
                MetodoPagamento.PIX)).join();
        confirmarPagamentoUseCase.executar(new ConfirmarPagamentoUseCase.Command(
                pagamentoConfirmado.pagamentoId(),
                "mercado-pago",
                "mp-confirmado-002")).join();

        var cancelados = cancelarPagamentosCriadosUseCase.executar(
                new CancelarPagamentosCriadosDaOrdemUseCase.Command(ordemServicoId)).join();

        assertEquals(1, cancelados.size());
        assertEquals(pagamentoCriado.pagamentoId(), cancelados.getFirst().pagamentoId());
        assertEquals(StatusPagamento.CANCELADO, cancelados.getFirst().status());
        var pagamentos = pagamentoRepository.findByOrdemServicoId(ordemServicoId).join();
        assertEquals(2, pagamentos.size());
        assertTrue(pagamentos.stream().anyMatch(pagamento ->
                pagamento.pagamentoId().equals(pagamentoConfirmado.pagamentoId())
                        && pagamento.status() == StatusPagamento.CONFIRMADO));
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
            Supplier<CompletableFuture<?>> executable) {
        var future = executable.get();
        var exception = assertThrows(CompletionException.class, future::join);
        return assertInstanceOf(expectedType, exception.getCause());
    }
}
