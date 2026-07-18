package br.com.oficina.billing.framework.web;

import static br.com.oficina.billing.framework.web.ResourceUniAdapter.toUni;

import br.com.oficina.billing.framework.payments.MercadoPagoWebhookSignatureValidator;
import br.com.oficina.billing.interfaces.controllers.PagamentoController;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/integracoes/mercado-pago/webhooks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
@PermitAll
public class MercadoPagoWebhookResource {
    private final PagamentoController pagamentoController;
    private final MercadoPagoWebhookSignatureValidator signatureValidator;

    public MercadoPagoWebhookResource(
            PagamentoController pagamentoController,
            MercadoPagoWebhookSignatureValidator signatureValidator) {
        this.pagamentoController = pagamentoController;
        this.signatureValidator = signatureValidator;
    }

    @POST
    public Uni<Response> receber(
            @HeaderParam("x-signature") String signature,
            @HeaderParam("x-request-id") String requestId,
            @QueryParam("data.id") String queryDataId,
            @QueryParam("type") String queryType,
            WebhookRequest request) {
        var dataId = dataId(queryDataId, request);
        if (!signatureValidator.isValid(signature, requestId, dataId)) {
            throw new NotAuthorizedException("Assinatura invalida do webhook do Mercado Pago.");
        }
        if (!isPayment(queryType, request)) {
            return Uni.createFrom().item(Response.noContent().build());
        }
        return toUni(() -> pagamentoController.reconciliarPagamentoPorTransacao(dataId)
                .thenApply(ignored -> Response.noContent().build()));
    }

    private String dataId(String queryDataId, WebhookRequest request) {
        var bodyDataId = request == null || request.data() == null ? null : request.data().id();
        var dataId = queryDataId == null || queryDataId.isBlank() ? bodyDataId : queryDataId;
        if (dataId == null || dataId.isBlank()) {
            throw new BadRequestException("Identificador do pagamento nao informado.");
        }
        if (bodyDataId != null && !bodyDataId.isBlank() && !dataId.equals(bodyDataId)) {
            throw new BadRequestException("Identificadores divergentes no webhook.");
        }
        return dataId;
    }

    private boolean isPayment(String queryType, WebhookRequest request) {
        var bodyType = request == null ? null : request.type();
        var type = queryType == null || queryType.isBlank() ? bodyType : queryType;
        return type == null || type.isBlank() || "payment".equalsIgnoreCase(type);
    }

    public record WebhookRequest(String type, Data data) {
        public record Data(String id) {
        }
    }
}
