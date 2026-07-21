package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.framework.payments.MercadoPagoWebhookSignatureValidator;
import br.com.oficina.billing.interfaces.controllers.PagamentoController;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MercadoPagoWebhookResourceTest {
    @Test
    void deveClassificarRelacaoEntreIdsDaQueryEDoCorpoSemExporValores() {
        var resource = new MercadoPagoWebhookResource(
                mock(PagamentoController.class),
                mock(MercadoPagoWebhookSignatureValidator.class));
        var body = new MercadoPagoWebhookResource.WebhookRequest(
                "order", new MercadoPagoWebhookResource.WebhookRequest.Data("order-1"));

        assertEquals("query_body_equal", resource.queryBodyDataIdRelation("order-1", body));
        assertEquals("query_body_different", resource.queryBodyDataIdRelation("order-2", body));
        assertEquals("query_missing", resource.queryBodyDataIdRelation(null, body));
        assertEquals("body_missing", resource.queryBodyDataIdRelation("order-1", null));
        assertEquals("both_missing", resource.queryBodyDataIdRelation(null, null));
    }

    @Test
    void deveClassificarFormatoDaQuerySemRegistrarSeuConteudo() {
        var resource = new MercadoPagoWebhookResource(
                mock(PagamentoController.class),
                mock(MercadoPagoWebhookSignatureValidator.class));
        var uriInfo = mock(UriInfo.class);
        resource.uriInfo = uriInfo;
        when(uriInfo.getRequestUri()).thenReturn(URI.create(
                "https://lab.example/api/v1/integracoes/mercado-pago/webhooks?data%2Eid=ORD-NAO-LOGAR&type=order"));

        assertEquals("data_encoded_dot_id", resource.queryDataIdFormat());
    }

    @Test
    void deveValidarAssinaturaEReconciliarPagamento() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        var payment = mock(Pagamento.class);
        when(validator.isValid("signature", "request-1", "123456")).thenReturn(true);
        when(controller.reconciliarPagamentoPorTransacao(
                        "123456",
                        TipoReferenciaExternaPagamento.PAYMENT))
                .thenReturn(CompletableFuture.completedFuture(payment));
        var resource = new MercadoPagoWebhookResource(controller, validator);

        var response = resource.receber(
                        "signature",
                        "request-1",
                        "123456",
                        "payment",
                        new MercadoPagoWebhookResource.WebhookRequest(
                                "payment.updated",
                                "payment",
                                new MercadoPagoWebhookResource.WebhookRequest.Data("123456")))
                .await().indefinitely();

        assertEquals(200, response.getStatus());
        verify(controller).reconciliarPagamentoPorTransacao(
                "123456",
                TipoReferenciaExternaPagamento.PAYMENT);
    }

    @Test
    void deveRejeitarAssinaturaInvalidaEIdentificadoresDivergentes() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        var resource = new MercadoPagoWebhookResource(controller, validator);
        var request = new MercadoPagoWebhookResource.WebhookRequest(
                "payment", new MercadoPagoWebhookResource.WebhookRequest.Data("123456"));

        assertThrows(
                NotAuthorizedException.class,
                () -> resource.receber("invalid", "request-1", "123456", "payment", request));
        assertThrows(
                BadRequestException.class,
                () -> resource.receber("signature", "request-1", "other", "payment", request));
        verifyNoInteractions(controller);
    }

    @Test
    void deveUsarIdentificadorETipoDoCorpo() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        when(validator.isValid("signature", "request-1", "123456")).thenReturn(true);
        when(controller.reconciliarPagamentoPorTransacao(
                        "123456",
                        TipoReferenciaExternaPagamento.PAYMENT))
                .thenReturn(CompletableFuture.completedFuture(mock(Pagamento.class)));
        var resource = new MercadoPagoWebhookResource(controller, validator);
        var request = new MercadoPagoWebhookResource.WebhookRequest(
                "payment", new MercadoPagoWebhookResource.WebhookRequest.Data("123456"));

        var response = resource.receber("signature", "request-1", null, null, request)
                .await().indefinitely();

        assertEquals(200, response.getStatus());
        verify(controller).reconciliarPagamentoPorTransacao(
                "123456",
                TipoReferenciaExternaPagamento.PAYMENT);
    }

    @Test
    void deveReconhecerReferenciaAssinadaDesconhecidaSemExporExistencia() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        when(validator.isValid("signature", "request-1", "123456")).thenReturn(true);
        when(controller.reconciliarPagamentoPorTransacao(
                        "123456",
                        TipoReferenciaExternaPagamento.PAYMENT))
                .thenReturn(CompletableFuture.failedFuture(
                        new ResourceNotFoundException("Pagamento do Mercado Pago nao encontrado.")));
        var resource = new MercadoPagoWebhookResource(controller, validator);

        var response = resource.receber(
                        "signature",
                        "request-1",
                        null,
                        null,
                        new MercadoPagoWebhookResource.WebhookRequest(
                                "payment.updated",
                                "payment",
                                new MercadoPagoWebhookResource.WebhookRequest.Data("123456")))
                .await().indefinitely();

        assertEquals(200, response.getStatus());
    }

    @Test
    void deveManterFalhaDeDependenciaRetentavel() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        when(validator.isValid("signature", "request-1", "payment-1")).thenReturn(true);
        when(controller.reconciliarPagamentoPorTransacao(
                        "payment-1",
                        TipoReferenciaExternaPagamento.PAYMENT))
                .thenReturn(CompletableFuture.failedFuture(
                        new BusinessException("DEPENDENCY_FAILURE", "Mercado Pago indisponivel.")));
        var resource = new MercadoPagoWebhookResource(controller, validator);

        assertThrows(
                BusinessException.class,
                () -> resource.receber(
                                "signature",
                                "request-1",
                                "payment-1",
                                "payment",
                                new MercadoPagoWebhookResource.WebhookRequest(
                                        "payment.updated",
                                        "payment",
                                        new MercadoPagoWebhookResource.WebhookRequest.Data("payment-1")))
                        .await().indefinitely());
    }

    @Test
    void deveIgnorarTipoNaoFinanceiroDepoisDeValidarAssinatura() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        when(validator.isValid("signature", "request-1", "123456")).thenReturn(true);
        var resource = new MercadoPagoWebhookResource(controller, validator);
        var request = new MercadoPagoWebhookResource.WebhookRequest(
                "merchant_order", new MercadoPagoWebhookResource.WebhookRequest.Data("123456"));

        var response = resource.receber("signature", "request-1", null, null, request)
                .await().indefinitely();

        assertEquals(200, response.getStatus());
        verifyNoInteractions(controller);
    }

    @Test
    void deveExigirIdentificadorNaQueryOuNoCorpo() {
        var resource = new MercadoPagoWebhookResource(
                mock(PagamentoController.class),
                mock(MercadoPagoWebhookSignatureValidator.class));

        assertThrows(
                BadRequestException.class,
                () -> resource.receber("signature", "request-1", null, "payment", null));
    }

    @Test
    void deveReconciliarOrderEValidarCoerenciaDeTipoEAction() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        var orderId = "ORD01JQ4S4KY8HWQ6NA5PXB65B3D3";
        when(validator.isValid("signature", "request-1", orderId)).thenReturn(true);
        when(controller.reconciliarPagamentoPorTransacao(
                        orderId,
                        TipoReferenciaExternaPagamento.ORDER))
                .thenReturn(CompletableFuture.completedFuture(mock(Pagamento.class)));
        var resource = new MercadoPagoWebhookResource(controller, validator);
        var request = new MercadoPagoWebhookResource.WebhookRequest(
                "order.action_required",
                "order",
                new MercadoPagoWebhookResource.WebhookRequest.Data(orderId));

        var response = resource.receber(
                        "signature",
                        "request-1",
                        null,
                        null,
                        request)
                .await().indefinitely();

        assertEquals(200, response.getStatus());
        verify(controller).reconciliarPagamentoPorTransacao(
                orderId,
                TipoReferenciaExternaPagamento.ORDER);

        assertThrows(
                BadRequestException.class,
                () -> resource.receber(
                        "signature",
                        "request-1",
                        orderId,
                        "payment",
                        request));
        assertThrows(
                BadRequestException.class,
                () -> resource.receber(
                        "signature",
                        "request-1",
                        orderId,
                        "order",
                        new MercadoPagoWebhookResource.WebhookRequest(
                                "payment.updated",
                                "order",
                                request.data())));
    }

    @Test
    void devePreservarCaixaDoDataIdEntreQueryValidacaoEConsultaDaOrder() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        var orderId = "ORD01JQ4S4KY8HWQ6NA5PXB65B3D3";
        when(validator.isValid("signature", "request-orders-case", orderId)).thenReturn(true);
        when(controller.reconciliarPagamentoPorTransacao(orderId, TipoReferenciaExternaPagamento.ORDER))
                .thenReturn(CompletableFuture.completedFuture(mock(Pagamento.class)));
        var resource = new MercadoPagoWebhookResource(controller, validator);
        var request = new MercadoPagoWebhookResource.WebhookRequest(
                "order.processed",
                "order",
                new MercadoPagoWebhookResource.WebhookRequest.Data(orderId));

        var response = resource.receber(
                        "signature",
                        "request-orders-case",
                        orderId,
                        "order",
                        request)
                .await().indefinitely();

        assertEquals(200, response.getStatus());
        verify(validator).isValid("signature", "request-orders-case", orderId);
        verify(controller).reconciliarPagamentoPorTransacao(orderId, TipoReferenciaExternaPagamento.ORDER);
    }
}
