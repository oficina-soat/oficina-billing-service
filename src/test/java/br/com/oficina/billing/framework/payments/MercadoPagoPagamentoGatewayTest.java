package br.com.oficina.billing.framework.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MercadoPagoPagamentoGatewayTest {
    @Test
    void deveIgnorarMercadoPagoQuandoIntegracaoEstiverDesabilitada() {
        var gateway = new MercadoPagoPagamentoGateway(
                (authorization, idempotencyKey, request) -> {
                    throw new AssertionError("Mercado Pago nao deveria ser chamado.");
                },
                false,
                Optional.empty(),
                "cliente.local@oficina.com");

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertFalse(resultado.integrado());
        assertEquals(StatusPagamento.CRIADO, resultado.status());
    }

    @Test
    void deveMapearPagamentoAprovadoComoConfirmado() {
        var gateway = new MercadoPagoPagamentoGateway(
                (authorization, idempotencyKey, request) -> {
                    assertEquals("Bearer token-teste", authorization);
                    assertEquals(request.externalReference(), idempotencyKey);
                    assertEquals("pix", request.paymentMethodId());
                    return new MercadoPagoPaymentResponse(123456L, "approved", "accredited");
                },
                true,
                Optional.of("token-teste"),
                "cliente.local@oficina.com");

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertTrue(resultado.integrado());
        assertEquals(StatusPagamento.CONFIRMADO, resultado.status());
        assertEquals("mercado-pago", resultado.provedor());
        assertEquals("123456", resultado.transacaoExternaId());
    }

    @Test
    void deveMapearPagamentoRecusadoComoRecusado() {
        var gateway = new MercadoPagoPagamentoGateway(
                (authorization, idempotencyKey, request) ->
                        new MercadoPagoPaymentResponse(654321L, "rejected", "cc_rejected_other_reason"),
                true,
                Optional.of("token-teste"),
                "cliente.local@oficina.com");

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertEquals(StatusPagamento.RECUSADO, resultado.status());
        assertEquals("654321", resultado.transacaoExternaId());
        assertEquals("cc_rejected_other_reason", resultado.motivo());
    }

    @Test
    void deveExigirTokenQuandoMercadoPagoEstiverHabilitado() {
        var gateway = new MercadoPagoPagamentoGateway(
                (authorization, idempotencyKey, request) -> new MercadoPagoPaymentResponse(1L, "pending", null),
                true,
                Optional.empty(),
                "cliente.local@oficina.com");
        var pagamento = pagamento(MetodoPagamento.PIX);

        var exception = assertThrows(BusinessException.class, () -> gateway.solicitar(pagamento));

        assertEquals("DEPENDENCY_UNAVAILABLE", exception.code());
    }

    @Test
    void deveRecusarMetodoSemSuporteNaIntegracaoDireta() {
        var gateway = new MercadoPagoPagamentoGateway(
                (authorization, idempotencyKey, request) -> new MercadoPagoPaymentResponse(1L, "pending", null),
                true,
                Optional.of("token-teste"),
                "cliente.local@oficina.com");
        var pagamento = pagamento(MetodoPagamento.CARTAO_CREDITO);

        var exception = assertThrows(
                BusinessException.class,
                () -> gateway.solicitar(pagamento));

        assertEquals("DEPENDENCY_UNAVAILABLE", exception.code());
    }

    private Pagamento pagamento(MetodoPagamento metodo) {
        var now = OffsetDateTime.now();
        return new Pagamento(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("100.00"),
                metodo,
                StatusPagamento.CRIADO,
                null,
                null,
                now,
                now);
    }
}
