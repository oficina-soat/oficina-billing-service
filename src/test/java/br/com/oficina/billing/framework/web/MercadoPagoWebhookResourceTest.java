package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.framework.payments.MercadoPagoWebhookSignatureValidator;
import br.com.oficina.billing.interfaces.controllers.PagamentoController;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MercadoPagoWebhookResourceTest {
    @Test
    void deveValidarAssinaturaEReconciliarPagamento() {
        var controller = mock(PagamentoController.class);
        var validator = mock(MercadoPagoWebhookSignatureValidator.class);
        var payment = mock(Pagamento.class);
        when(validator.isValid("signature", "request-1", "123456")).thenReturn(true);
        when(controller.reconciliarPagamentoPorTransacao("123456"))
                .thenReturn(CompletableFuture.completedFuture(payment));
        var resource = new MercadoPagoWebhookResource(controller, validator);

        var response = resource.receber(
                        "signature",
                        "request-1",
                        "123456",
                        "payment",
                        new MercadoPagoWebhookResource.WebhookRequest(
                                "payment", new MercadoPagoWebhookResource.WebhookRequest.Data("123456")))
                .await().indefinitely();

        assertEquals(204, response.getStatus());
        verify(controller).reconciliarPagamentoPorTransacao("123456");
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
        when(controller.reconciliarPagamentoPorTransacao("123456"))
                .thenReturn(CompletableFuture.completedFuture(mock(Pagamento.class)));
        var resource = new MercadoPagoWebhookResource(controller, validator);
        var request = new MercadoPagoWebhookResource.WebhookRequest(
                "payment", new MercadoPagoWebhookResource.WebhookRequest.Data("123456"));

        var response = resource.receber("signature", "request-1", null, null, request)
                .await().indefinitely();

        assertEquals(204, response.getStatus());
        verify(controller).reconciliarPagamentoPorTransacao("123456");
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

        assertEquals(204, response.getStatus());
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
}
