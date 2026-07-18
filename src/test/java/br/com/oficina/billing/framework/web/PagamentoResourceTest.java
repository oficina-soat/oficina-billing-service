package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.oficina.billing.core.entities.InstrucoesPix;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import br.com.oficina.billing.core.usecases.pagamento.ReconciliarPagamentoUseCase;
import br.com.oficina.billing.interfaces.controllers.PagamentoController;
import br.com.oficina.billing.interfaces.presenters.PagamentoPresenterAdapter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PagamentoResourceTest {
    @Test
    void deveReconciliarEApresentarInstrucoesPix() {
        var controller = mock(PagamentoController.class);
        var payment = payment();
        when(controller.reconciliarPagamento(payment.pagamentoId()))
                .thenReturn(CompletableFuture.completedFuture(payment));
        var resource = new PagamentoResource(controller, new PagamentoPresenterAdapter());

        var response = resource.reconciliarPagamento(payment.pagamentoId()).await().indefinitely();

        assertEquals(payment.pagamentoId(), response.pagamentoId());
        assertEquals(payment.instrucoesPix().copiaECola(), response.instrucoesPix().copiaECola());
        assertEquals(payment.instrucoesPix().expiraEm(), response.instrucoesPix().expiraEm());
        assertEquals(payment.acoesPermitidas(), response.acoesPermitidas());
    }

    @Test
    void deveConstruirCasoDeUsoDeReconciliacaoNaConfiguracao() {
        var useCase = new BillingConfiguration().reconciliarPagamentoUseCase(
                mock(PagamentoRepositoryGateway.class),
                mock(PagamentoGateway.class),
                mock(OutboxEventSender.class));

        assertInstanceOf(ReconciliarPagamentoUseCase.class, useCase);
    }

    private Pagamento payment() {
        var now = OffsetDateTime.parse("2026-07-18T18:00:00Z");
        return new Pagamento(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("190.00"),
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                "mercado-pago",
                "mp-123",
                new InstrucoesPix("pix-code", "base64", "https://example.test/pix", now.plusMinutes(30)),
                now,
                now);
    }
}
