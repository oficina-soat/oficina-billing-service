package br.com.oficina.billing.core.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.PagamentoGatewayResult;
import br.com.oficina.billing.framework.db.InMemoryOrcamentoRepository;
import br.com.oficina.billing.framework.db.InMemoryPagamentoRepository;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PagamentoServiceTest {
    private final BillingEventStore eventStore = new BillingEventStore();
    private final OrcamentoService orcamentoService = new OrcamentoService(new InMemoryOrcamentoRepository(), eventStore);

    @Test
    void deveRegistrarPagamentoConfirmadoPeloGatewayEPublicarEventos() {
        var service = new PagamentoService(
                new InMemoryPagamentoRepository(),
                orcamentoService,
                eventStore,
                pagamento -> PagamentoGatewayResult.confirmado("mercado-pago", "mp-confirmado-001"));
        var orcamento = orcamentoService.aprovar(orcamentoService.gerar(UUID.randomUUID()).orcamentoId(), null);

        var pagamento = service.registrar(orcamento.ordemServicoId(), orcamento.orcamentoId(), BigDecimal.ZERO, MetodoPagamento.PIX);

        assertEquals(StatusPagamento.CONFIRMADO, pagamento.status());
        assertEquals("mercado-pago", pagamento.provedor());
        assertEquals("mp-confirmado-001", pagamento.transacaoExternaId());
        assertTrue(eventStore.listarOutbox().stream().anyMatch(event -> event.eventType().equals("pagamentoSolicitado")));
        assertTrue(eventStore.listarOutbox().stream().anyMatch(event -> event.eventType().equals("pagamentoConfirmado")));
    }

    @Test
    void deveRejeitarPagamentoQuandoOrcamentoNaoPertenceAOrdemOuNaoEstaAprovado() {
        var service = new PagamentoService(new InMemoryPagamentoRepository(), orcamentoService, eventStore);
        var orcamentoGerado = orcamentoService.gerar(UUID.randomUUID());

        var erroEstado = assertThrows(BusinessException.class, () ->
                service.registrar(orcamentoGerado.ordemServicoId(), orcamentoGerado.orcamentoId(), BigDecimal.ZERO, MetodoPagamento.PIX));
        assertEquals("INVALID_STATE_TRANSITION", erroEstado.code());

        var orcamentoAprovado = orcamentoService.aprovar(orcamentoService.gerar(UUID.randomUUID()).orcamentoId(), null);
        var erroOwnership = assertThrows(BusinessException.class, () ->
                service.registrar(UUID.randomUUID(), orcamentoAprovado.orcamentoId(), BigDecimal.ZERO, MetodoPagamento.PIX));
        assertEquals("BUSINESS_RULE_VIOLATION", erroOwnership.code());
    }

    @Test
    void deveSolicitarPagamentoDaOrdemDeFormaIdempotentePorOrcamento() {
        var service = new PagamentoService(new InMemoryPagamentoRepository(), orcamentoService, eventStore);
        var orcamento = orcamentoService.aprovar(orcamentoService.gerar(UUID.randomUUID()).orcamentoId(), null);

        var primeiro = service.solicitarPagamentoDaOrdem(orcamento.ordemServicoId());
        var segundo = service.solicitarPagamentoDaOrdem(orcamento.ordemServicoId());

        assertEquals(primeiro.pagamentoId(), segundo.pagamentoId());
        assertEquals(1, service.consultarPorOrdemServico(orcamento.ordemServicoId()).size());
    }

    @Test
    void deveFalharAoConsultarPagamentoInexistente() {
        var service = new PagamentoService(new InMemoryPagamentoRepository(), orcamentoService, eventStore);

        assertThrows(ResourceNotFoundException.class, () -> service.consultar(UUID.randomUUID()));
    }
}
