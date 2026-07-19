package br.com.oficina.billing.framework.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MercadoPagoOrdersGatewayTest {
    @Test
    void deveCriarOrderPixAproComIdentidadeEInstrucoes() {
        var pagamento = pagamento();
        MercadoPagoOrderClient client = new MercadoPagoOrderClient() {
            @Override
            public MercadoPagoOrderResponse createOrder(
                    String authorization,
                    String idempotencyKey,
                    MercadoPagoOrderRequest request) {
                assertEquals("Bearer token-orders", authorization);
                assertEquals(pagamento.pagamentoId().toString(), idempotencyKey);
                assertEquals("online", request.type());
                assertEquals("automatic", request.processingMode());
                assertEquals(pagamento.pagamentoId().toString(), request.externalReference());
                assertEquals(0, pagamento.valor().compareTo(request.totalAmount()));
                assertEquals("test_user_br@testuser.com", request.payer().email());
                assertEquals("APRO", request.payer().firstName());
                assertEquals(1, request.transactions().payments().size());
                assertEquals("pix", request.transactions().payments().getFirst().paymentMethod().id());
                assertEquals("bank_transfer", request.transactions().payments().getFirst().paymentMethod().type());
                assertPayloadMonetarioComoString(request);
                return orderResponse(
                        pagamento,
                        "order-001",
                        "action_required",
                        "waiting_transfer",
                        "pix-copia-e-cola");
            }

            @Override
            public MercadoPagoOrderResponse getOrder(String authorization, String orderId) {
                throw new AssertionError("Consulta nao deveria ser chamada.");
            }
        };
        var gateway = gateway(client, MercadoPagoPagamentoGateway.ApiMode.ORDERS, Optional.of("APRO"), "test");

        var result = gateway.solicitar(pagamento).join();

        assertEquals(StatusPagamento.CRIADO, result.status());
        assertEquals("order-001", result.transacaoExternaId());
        assertEquals(TipoReferenciaExternaPagamento.ORDER, result.tipoReferenciaExterna());
        assertEquals("pix-copia-e-cola", result.instrucoesPix().copiaECola());
        assertEquals("base64", result.instrucoesPix().qrCodeBase64());
        assertEquals("https://example.test/pix", result.instrucoesPix().ticketUrl());
        assertNull(result.instrucoesPix().expiraEm());
    }

    @Test
    void deveTraduzirTodosOsEstadosOrdersContratados() {
        var pagamento = pagamento();
        var response = new MercadoPagoOrderResponse[1];
        var gateway = gateway(orderClient(response), MercadoPagoPagamentoGateway.ApiMode.ORDERS, Optional.empty(), "test");

        for (var pending : List.of(
                new String[] {"created", "created"},
                new String[] {"processing", "processing"},
                new String[] {"action_required", "waiting_payment"},
                new String[] {"action_required", "waiting_transfer"})) {
            response[0] = orderResponse(pagamento, "order-pending", pending[0], pending[1], null);
            assertEquals(StatusPagamento.CRIADO, gateway.solicitar(pagamento).join().status());
        }

        response[0] = orderResponse(pagamento, "order-approved", "processed", "accredited", null);
        assertEquals(StatusPagamento.CONFIRMADO, gateway.solicitar(pagamento).join().status());

        for (var terminal : List.of("failed", "canceled", "expired", "refunded", "charged_back")) {
            response[0] = orderResponse(pagamento, "order-negative", terminal, "provider_reason", null);
            var result = gateway.solicitar(pagamento).join();
            assertEquals(StatusPagamento.RECUSADO, result.status());
            assertEquals("provider_reason", result.motivo());
        }
    }

    @Test
    void deveConsultarPeloTipoPersistidoMesmoComCriacaoEmModoPayments() {
        var pagamento = pagamentoOrder("order-query");
        MercadoPagoOrderClient client = new MercadoPagoOrderClient() {
            @Override
            public MercadoPagoOrderResponse createOrder(
                    String authorization,
                    String idempotencyKey,
                    MercadoPagoOrderRequest request) {
                throw new AssertionError("Criacao nao deveria ser chamada.");
            }

            @Override
            public MercadoPagoOrderResponse getOrder(String authorization, String orderId) {
                assertEquals("Bearer token-orders", authorization);
                assertEquals("order-query", orderId);
                return orderResponse(
                        pagamento,
                        orderId,
                        "processed",
                        "accredited",
                        null);
            }
        };
        var gateway = gateway(client, MercadoPagoPagamentoGateway.ApiMode.PAYMENTS, Optional.empty(), "test");

        var result = gateway.consultar(pagamento).join();

        assertEquals(StatusPagamento.CONFIRMADO, result.status());
        assertEquals(TipoReferenciaExternaPagamento.ORDER, result.tipoReferenciaExterna());
    }

    @Test
    void deveRejeitarOrderInconsistenteOuEstadoDesconhecido() {
        var pagamento = pagamento();
        var response = new MercadoPagoOrderResponse[1];
        var gateway = gateway(orderClient(response), MercadoPagoPagamentoGateway.ApiMode.ORDERS, Optional.empty(), "test");

        response[0] = orderResponse(pagamento, "order-invalid", "processed", "pending", null);
        assertDependencyFailure(() -> gateway.solicitar(pagamento));

        response[0] = orderResponse(pagamento, "order-invalid", "action_required", "unknown", null);
        assertDependencyFailure(() -> gateway.solicitar(pagamento));

        response[0] = orderResponse(pagamento, "order-invalid", "unknown", "unknown", null);
        assertDependencyFailure(() -> gateway.solicitar(pagamento));

        var valid = orderResponse(pagamento, "order-invalid", "created", "created", null);
        response[0] = new MercadoPagoOrderResponse(
                valid.id(),
                valid.status(),
                valid.statusDetail(),
                UUID.randomUUID().toString(),
                valid.totalAmount(),
                valid.transactions());
        assertDependencyFailure(() -> gateway.solicitar(pagamento));

        response[0] = new MercadoPagoOrderResponse(
                valid.id(),
                valid.status(),
                valid.statusDetail(),
                valid.externalReference(),
                BigDecimal.ONE,
                valid.transactions());
        assertDependencyFailure(() -> gateway.solicitar(pagamento));
    }

    @Test
    void deveProibirAproForaDoSandboxEValidarModo() {
        var client = orderClient(new MercadoPagoOrderResponse[1]);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway(client, MercadoPagoPagamentoGateway.ApiMode.ORDERS, Optional.of("APRO"), "prod"));
        assertEquals(
                MercadoPagoPagamentoGateway.ApiMode.ORDERS,
                MercadoPagoPagamentoGateway.ApiMode.from("orders"));
        assertEquals(
                MercadoPagoPagamentoGateway.ApiMode.PAYMENTS,
                MercadoPagoPagamentoGateway.ApiMode.from(" payments "));
        assertThrows(
                IllegalArgumentException.class,
                () -> MercadoPagoPagamentoGateway.ApiMode.from("unknown"));
    }

    @Test
    void deveOmitirInstrucoesQuandoOrderNaoContiverCodigoPix() {
        var pagamento = pagamento();
        var response = new MercadoPagoOrderResponse[] {
                orderResponse(pagamento, "order-no-pix", "created", "created", " ")
        };
        var gateway = gateway(orderClient(response), MercadoPagoPagamentoGateway.ApiMode.ORDERS, Optional.empty(), "test");

        assertNull(gateway.solicitar(pagamento).join().instrucoesPix());
    }

    private MercadoPagoPagamentoGateway gateway(
            MercadoPagoOrderClient orderClient,
            MercadoPagoPagamentoGateway.ApiMode mode,
            Optional<String> firstName,
            String environment) {
        var registry = new SimpleMeterRegistry();
        MercadoPagoClient paymentClient = (authorization, idempotencyKey, request) -> {
            throw new AssertionError("Payment legado nao deveria ser criado.");
        };
        MercadoPagoQueryClient queryClient = (authorization, paymentId) -> {
            throw new AssertionError("Payment legado nao deveria ser consultado.");
        };
        return new MercadoPagoPagamentoGateway(
                paymentClient,
                queryClient,
                orderClient,
                new MercadoPagoMetrics(registry, true, environment),
                true,
                Optional.of("token-orders"),
                "test_user_br@testuser.com",
                firstName,
                mode,
                environment);
    }

    private MercadoPagoOrderClient orderClient(MercadoPagoOrderResponse[] response) {
        return new MercadoPagoOrderClient() {
            @Override
            public MercadoPagoOrderResponse createOrder(
                    String authorization,
                    String idempotencyKey,
                    MercadoPagoOrderRequest request) {
                return response[0];
            }

            @Override
            public MercadoPagoOrderResponse getOrder(String authorization, String orderId) {
                return response[0];
            }
        };
    }

    private MercadoPagoOrderResponse orderResponse(
            Pagamento pagamento,
            String id,
            String status,
            String statusDetail,
            String qrCode) {
        return new MercadoPagoOrderResponse(
                id,
                status,
                statusDetail,
                pagamento.pagamentoId().toString(),
                pagamento.valor(),
                new MercadoPagoOrderResponse.Transactions(List.of(
                        new MercadoPagoOrderResponse.Payment(
                                "provider-payment-1",
                                pagamento.valor(),
                                status,
                                statusDetail,
                                new MercadoPagoOrderResponse.PaymentMethod(
                                        "pix",
                                        "bank_transfer",
                                        "https://example.test/pix",
                                        qrCode,
                                        "base64")))));
    }

    private Pagamento pagamento() {
        var now = OffsetDateTime.parse("2026-07-19T12:00:00Z");
        return new Pagamento(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("100.00"),
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                null,
                null,
                now,
                now);
    }

    private Pagamento pagamentoOrder(String orderId) {
        var base = pagamento();
        return new Pagamento(
                base.pagamentoId(),
                base.ordemServicoId(),
                base.orcamentoId(),
                base.valor(),
                base.metodo(),
                base.status(),
                "mercado-pago",
                orderId,
                TipoReferenciaExternaPagamento.ORDER,
                null,
                base.criadoEm(),
                base.atualizadoEm());
    }

    private void assertDependencyFailure(Runnable operation) {
        var exception = assertThrows(BusinessException.class, operation::run);
        assertEquals("DEPENDENCY_FAILURE", exception.code());
    }

    private void assertPayloadMonetarioComoString(MercadoPagoOrderRequest request) {
        try {
            var json = new ObjectMapper().writeValueAsString(request);
            org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"total_amount\":\"100.00\""));
            org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"amount\":\"100.00\""));
        } catch (JsonProcessingException exception) {
            throw new AssertionError("Nao foi possivel serializar a order.", exception);
        }
    }
}
