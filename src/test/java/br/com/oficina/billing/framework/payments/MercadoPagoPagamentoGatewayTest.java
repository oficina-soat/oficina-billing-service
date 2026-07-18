package br.com.oficina.billing.framework.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MercadoPagoPagamentoGatewayTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void deveIgnorarMercadoPagoQuandoIntegracaoEstiverDesabilitada() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> {
                    throw new AssertionError("Mercado Pago nao deveria ser chamado.");
                },
                false,
                Optional.empty());

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertFalse(resultado.integrado());
        assertEquals(StatusPagamento.CRIADO, resultado.status());
        assertEquals(0.0, registry.get(MercadoPagoMetrics.ENABLED).gauge().value());
        assertEquals(1.0, requestCount("not_integrated", "none"));
        assertLowCardinalityTags();
    }

    @Test
    void deveMapearPagamentoAprovadoComoConfirmado() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> {
                    assertEquals("Bearer token-teste", authorization);
                    assertEquals(request.externalReference(), idempotencyKey);
                    assertEquals("pix", request.paymentMethodId());
                    return new MercadoPagoPaymentResponse(123456L, "approved", "accredited");
                },
                true,
                Optional.of("token-teste"));

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertTrue(resultado.integrado());
        assertEquals(StatusPagamento.CONFIRMADO, resultado.status());
        assertEquals("mercado-pago", resultado.provedor());
        assertEquals("123456", resultado.transacaoExternaId());
        assertEquals(1.0, requestCount("confirmed", "approved"));
        assertEquals(1, timerCount("confirmed"));
        assertEquals(100.0, amount("confirmed"));
        assertEquals(1.0, registry.get(MercadoPagoMetrics.ENABLED).gauge().value());
        assertLowCardinalityTags();
    }

    @Test
    void deveMapearPagamentoRecusadoComoRecusado() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) ->
                        new MercadoPagoPaymentResponse(654321L, "rejected", "cc_rejected_other_reason"),
                true,
                Optional.of("token-teste"));

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertEquals(StatusPagamento.RECUSADO, resultado.status());
        assertEquals("654321", resultado.transacaoExternaId());
        assertEquals("cc_rejected_other_reason", resultado.motivo());
        assertEquals(1.0, requestCount("rejected", "rejected"));
        assertEquals(1.0, failureCount("business_rejection"));
    }

    @Test
    void deveMapearPagamentoPendenteComoCriado() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) ->
                        new MercadoPagoPaymentResponse(777L, "pending", null),
                true,
                Optional.of("token-teste"));

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertEquals(StatusPagamento.CRIADO, resultado.status());
        assertEquals("777", resultado.transacaoExternaId());
        assertEquals(1.0, requestCount("pending", "pending"));
    }

    @Test
    void deveUsarDetalhePadraoParaPagamentoRecusadoSemMotivo() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) ->
                        new MercadoPagoPaymentResponse(888L, "cancelled", " "),
                true,
                Optional.of("token-teste"));

        var resultado = gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertEquals(StatusPagamento.RECUSADO, resultado.status());
        assertEquals("Pagamento nao autorizado pelo Mercado Pago.", resultado.motivo());
    }

    @Test
    void deveExigirTokenQuandoMercadoPagoEstiverHabilitado() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> new MercadoPagoPaymentResponse(1L, "pending", null),
                true,
                Optional.empty());
        var pagamento = pagamento(MetodoPagamento.PIX);

        var exception = assertThrows(BusinessException.class, () -> gateway.solicitar(pagamento));

        assertEquals("DEPENDENCY_UNAVAILABLE", exception.code());
        assertEquals(1.0, failureCount("configuration"));
        assertEquals(1.0, unavailableCount("configuration"));
    }

    @Test
    void deveRecusarMetodoSemSuporteNaIntegracaoDireta() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> new MercadoPagoPaymentResponse(1L, "pending", null),
                true,
                Optional.of("token-teste"));
        var pagamento = pagamento(MetodoPagamento.CARTAO_CREDITO);

        var exception = assertThrows(
                BusinessException.class,
                () -> gateway.solicitar(pagamento));

        assertEquals("DEPENDENCY_UNAVAILABLE", exception.code());
        assertEquals(1.0, failureCount("unsupported_method", "CARTAO_CREDITO"));
    }

    @Test
    void deveRejeitarRespostaInvalidaDoMercadoPago() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> null,
                true,
                Optional.of("token-teste"));
        var pagamento = pagamento(MetodoPagamento.PIX);

        var exception = assertThrows(BusinessException.class, () -> gateway.solicitar(pagamento));

        assertEquals("DEPENDENCY_FAILURE", exception.code());
        assertEquals(1.0, failureCount("invalid_response"));
    }

    @Test
    void deveMapearFalhaHttpDoMercadoPago() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> {
                    throw new WebApplicationException(Response.status(Response.Status.BAD_GATEWAY).build());
                },
                true,
                Optional.of("token-teste"));
        var pagamento = pagamento(MetodoPagamento.PIX);

        var exception = assertThrows(BusinessException.class, () -> gateway.solicitar(pagamento));

        assertEquals("DEPENDENCY_FAILURE", exception.code());
        assertEquals(1.0, failureCount("provider_http_error"));
        assertEquals(1.0, unavailableCount("provider_http_error"));
    }

    @Test
    void deveMapearFalhaDeComunicacaoDoMercadoPago() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> {
                    throw new ProcessingException("timeout");
                },
                true,
                Optional.of("token-teste"));
        var pagamento = pagamento(MetodoPagamento.PIX);

        var exception = assertThrows(BusinessException.class, () -> gateway.solicitar(pagamento));

        assertEquals("DEPENDENCY_FAILURE", exception.code());
        assertEquals(1.0, failureCount("timeout"));
        assertEquals(1.0, unavailableCount("timeout"));
    }

    @Test
    void deveCategorizarIndisponibilidadeSemTimeoutComoFalhaDeComunicacao() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) -> {
                    throw new ProcessingException("connection refused");
                },
                true,
                Optional.of("token-teste"));
        var pagamento = pagamento(MetodoPagamento.PIX);

        assertThrows(BusinessException.class, () -> gateway.solicitar(pagamento));

        assertEquals(1.0, failureCount("communication"));
        assertEquals(1.0, unavailableCount("communication"));
    }

    @Test
    void deveAgruparStatusDesconhecidoSemCriarTagDeAltaCardinalidade() {
        var gateway = gateway(
                (authorization, idempotencyKey, request) ->
                        new MercadoPagoPaymentResponse(999L, "waiting_provider_custom_status", null),
                true,
                Optional.of("token-teste"));

        gateway.solicitar(pagamento(MetodoPagamento.PIX)).join();

        assertEquals(1.0, requestCount("pending", "other"));
        assertLowCardinalityTags();
    }

    @Test
    void deveConsultarPagamentoEPreservarInstrucoesPixPendentes() {
        var expiration = OffsetDateTime.parse("2026-01-01T12:30:00Z");
        MercadoPagoQueryClient queryClient = (authorization, paymentId) -> {
            assertEquals("Bearer token-teste", authorization);
            assertEquals("123456", paymentId);
            return new MercadoPagoPaymentResponse(
                    123456L,
                    "pending",
                    "pending_waiting_payment",
                    expiration,
                    new MercadoPagoPaymentResponse.PointOfInteraction(
                            new MercadoPagoPaymentResponse.TransactionData(
                                    "pix-copia-e-cola", "base64", "https://example.test/pix")));
        };
        var gateway = new MercadoPagoPagamentoGateway(
                (authorization, idempotencyKey, request) -> {
                    throw new AssertionError("Criacao nao deveria ser chamada.");
                },
                queryClient,
                new MercadoPagoMetrics(registry, true, "test"),
                true,
                Optional.of("token-teste"),
                "cliente.local@oficina.com");
        var base = pagamento(MetodoPagamento.PIX);
        var integrated = new Pagamento(
                base.pagamentoId(),
                base.ordemServicoId(),
                base.orcamentoId(),
                base.valor(),
                base.metodo(),
                base.status(),
                "mercado-pago",
                "123456",
                base.criadoEm(),
                base.atualizadoEm());

        var result = gateway.consultar(integrated).join();

        assertEquals(StatusPagamento.CRIADO, result.status());
        assertEquals("pix-copia-e-cola", result.instrucoesPix().copiaECola());
        assertEquals(expiration, result.instrucoesPix().expiraEm());
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

    private MercadoPagoPagamentoGateway gateway(
            MercadoPagoClient client,
            boolean enabled,
            Optional<String> accessToken) {
        return new MercadoPagoPagamentoGateway(
                client,
                new MercadoPagoMetrics(registry, enabled, "test"),
                enabled,
                accessToken,
                "cliente.local@oficina.com");
    }

    private double requestCount(String outcome, String providerStatus) {
        return registry.get(MercadoPagoMetrics.REQUESTS)
                .tags("method", "PIX", "outcome", outcome, "providerStatus", providerStatus)
                .counter()
                .count();
    }

    private long timerCount(String outcome) {
        return registry.get(MercadoPagoMetrics.DURATION)
                .tags("method", "PIX", "outcome", outcome)
                .timer()
                .count();
    }

    private double amount(String outcome) {
        return registry.get(MercadoPagoMetrics.AMOUNT)
                .tags("method", "PIX", "outcome", outcome, "currency", "BRL")
                .summary()
                .totalAmount();
    }

    private double failureCount(String reason) {
        return failureCount(reason, "PIX");
    }

    private double failureCount(String reason, String method) {
        return registry.get(MercadoPagoMetrics.FAILURES)
                .tags("method", method, "reason", reason)
                .counter()
                .count();
    }

    private double unavailableCount(String reason) {
        return registry.get(MercadoPagoMetrics.UNAVAILABLE)
                .tag("reason", reason)
                .counter()
                .count();
    }

    private void assertLowCardinalityTags() {
        var forbiddenTags = Set.of(
                "pagamentoId",
                "ordemServicoId",
                "transacaoExternaId",
                "cpf",
                "email",
                "correlationId");
        assertTrue(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> forbiddenTags.contains(tag.getKey())));
    }
}
