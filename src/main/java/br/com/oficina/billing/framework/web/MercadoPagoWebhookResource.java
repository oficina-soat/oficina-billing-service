package br.com.oficina.billing.framework.web;

import static br.com.oficina.billing.framework.web.ResourceUniAdapter.toUni;

import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.framework.observability.StructuredLog;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.jboss.logging.Logger;

@Path("/api/v1/integracoes/mercado-pago/webhooks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
@PermitAll
public class MercadoPagoWebhookResource {
    private static final Logger LOG = Logger.getLogger(MercadoPagoWebhookResource.class);

    private final PagamentoController pagamentoController;
    private final MercadoPagoWebhookSignatureValidator signatureValidator;

    @Context
    UriInfo uriInfo;

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
        auditRequestShape(queryDataId, queryType, request);
        if (!signatureValidator.isValid(signature, requestId, dataId)) {
            throw new NotAuthorizedException("Assinatura invalida do webhook do Mercado Pago.");
        }
        var tipoReferenciaExterna = tipoReferenciaExterna(queryType, request);
        if (tipoReferenciaExterna == null) {
            return Uni.createFrom().item(Response.ok().build());
        }
        validarAction(request, tipoReferenciaExterna);
        return toUni(() -> pagamentoController.reconciliarPagamentoPorTransacao(
                        dataId,
                        tipoReferenciaExterna)
                .thenApply(ignored -> Response.ok().build()))
                .onFailure(ResourceNotFoundException.class)
                .recoverWithItem(Response.ok().build());
    }

    private void auditRequestShape(String queryDataId, String queryType, WebhookRequest request) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("webhookDataIdSource", queryDataId == null || queryDataId.isBlank() ? "body" : "query");
        fields.put("webhookTypeSource", queryType == null || queryType.isBlank() ? "body" : "query");
        fields.put("webhookQueryDataIdFormat", queryDataIdFormat());
        fields.put("webhookQueryParameterCount", uriInfo == null ? -1 : uriInfo.getQueryParameters().size());
        fields.put("webhookBodyDataPresent", request != null && request.data() != null);
        StructuredLog.info(LOG, "mercado pago webhook request shape received", fields);
    }

    String queryDataIdFormat() {
        if (uriInfo == null || uriInfo.getRequestUri() == null) {
            return "unavailable";
        }
        var rawQuery = uriInfo.getRequestUri().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return "absent";
        }
        var normalized = rawQuery.toLowerCase(Locale.ROOT);
        if (normalized.contains("data.id=")) {
            return "data_dot_id";
        }
        if (normalized.contains("data%2eid=")) {
            return "data_encoded_dot_id";
        }
        if (normalized.contains("data_id=")) {
            return "data_underscore_id";
        }
        if (normalized.contains("id=")) {
            return "id";
        }
        return "other";
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

    private TipoReferenciaExternaPagamento tipoReferenciaExterna(
            String queryType,
            WebhookRequest request) {
        var bodyType = request == null ? null : request.type();
        if (queryType != null
                && !queryType.isBlank()
                && bodyType != null
                && !bodyType.isBlank()
                && !queryType.equalsIgnoreCase(bodyType)) {
            throw new BadRequestException("Tipos divergentes no webhook.");
        }
        var type = queryType == null || queryType.isBlank() ? bodyType : queryType;
        if ("order".equalsIgnoreCase(type)) {
            return TipoReferenciaExternaPagamento.ORDER;
        }
        if ("payment".equalsIgnoreCase(type)) {
            return TipoReferenciaExternaPagamento.PAYMENT;
        }
        return null;
    }

    private void validarAction(
            WebhookRequest request,
            TipoReferenciaExternaPagamento tipoReferenciaExterna) {
        var action = request == null ? null : request.action();
        if (action == null || action.isBlank()) {
            return;
        }
        var prefix = tipoReferenciaExterna == TipoReferenciaExternaPagamento.ORDER
                ? "order."
                : "payment.";
        if (!action.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
            throw new BadRequestException("Action divergente do tipo no webhook.");
        }
    }

    public record WebhookRequest(String action, String type, Data data) {
        public WebhookRequest(String type, Data data) {
            this(null, type, data);
        }

        public record Data(String id) {
        }
    }
}
