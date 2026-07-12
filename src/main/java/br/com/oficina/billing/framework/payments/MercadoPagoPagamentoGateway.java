package br.com.oficina.billing.framework.payments;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class MercadoPagoPagamentoGateway implements PagamentoGateway {
    private static final String PROVEDOR = "mercado-pago";

    private final MercadoPagoClient client;
    private final MercadoPagoMetrics metrics;
    private final boolean enabled;
    private final Optional<String> accessToken;
    private final String payerEmail;

    public MercadoPagoPagamentoGateway(
            @RestClient MercadoPagoClient client,
            MercadoPagoMetrics metrics,
            @ConfigProperty(name = "oficina.mercado-pago.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "oficina.mercado-pago.access-token") Optional<String> accessToken,
            @ConfigProperty(name = "oficina.mercado-pago.payer-email", defaultValue = "cliente.local@oficina.com") String payerEmail) {
        this.client = client;
        this.metrics = metrics;
        this.enabled = enabled;
        this.accessToken = accessToken;
        this.payerEmail = payerEmail;
    }

    @Override
    public CompletableFuture<PagamentoGatewayResult> solicitar(Pagamento pagamento) {
        return CompletableFuture.completedFuture(solicitarPagamento(pagamento));
    }

    private PagamentoGatewayResult solicitarPagamento(Pagamento pagamento) {
        if (!enabled) {
            metrics.recordNotIntegrated(pagamento);
            return PagamentoGatewayResult.naoIntegrado();
        }
        if (pagamento.metodo() != MetodoPagamento.PIX) {
            metrics.recordFailure(pagamento, "unsupported_method", false, null);
            throw new BusinessException(
                    "DEPENDENCY_UNAVAILABLE",
                    "Mercado Pago esta habilitado apenas para pagamentos PIX nesta versao.");
        }
        if (accessToken.isEmpty() || accessToken.get().isBlank()) {
            metrics.recordFailure(pagamento, "configuration", true, null);
            throw new BusinessException(
                    "DEPENDENCY_UNAVAILABLE",
                    "Token de acesso do Mercado Pago nao configurado.");
        }

        var request = new MercadoPagoPaymentRequest(
                pagamento.valor(),
                "Ordem de servico " + pagamento.ordemServicoId(),
                "pix",
                pagamento.pagamentoId().toString(),
                new MercadoPagoPaymentRequest.Payer(payerEmail));
        var sample = metrics.startRequest();
        try {
            var response = client.createPayment(
                    "Bearer " + accessToken.get(),
                    pagamento.pagamentoId().toString(),
                    request);
            var result = toResult(response);
            metrics.recordResult(pagamento, result, response.status(), sample);
            return result;
        } catch (WebApplicationException exception) {
            var status = exception.getResponse().getStatus();
            metrics.recordFailure(pagamento, "provider_http_error", status >= 500, sample);
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Mercado Pago recusou a solicitacao de pagamento com HTTP "
                            + status + ".");
        } catch (ProcessingException exception) {
            var reason = isTimeout(exception) ? "timeout" : "communication";
            metrics.recordFailure(pagamento, reason, true, sample);
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Falha de comunicacao com Mercado Pago ao solicitar pagamento.");
        } catch (BusinessException exception) {
            metrics.recordFailure(pagamento, "invalid_response", false, sample);
            throw exception;
        }
    }

    private PagamentoGatewayResult toResult(MercadoPagoPaymentResponse response) {
        if (response == null || response.id() == null || response.status() == null || response.status().isBlank()) {
            throw new BusinessException("DEPENDENCY_FAILURE", "Resposta invalida do Mercado Pago.");
        }

        var transacaoExternaId = response.id().toString();
        var status = response.status().trim().toLowerCase();
        return switch (status) {
            case "approved" -> PagamentoGatewayResult.confirmado(PROVEDOR, transacaoExternaId);
            case "rejected", "cancelled", "refunded", "charged_back" ->
                    PagamentoGatewayResult.recusado(PROVEDOR, transacaoExternaId, detalhe(response));
            default -> PagamentoGatewayResult.criado(PROVEDOR, transacaoExternaId);
        };
    }

    private String detalhe(MercadoPagoPaymentResponse response) {
        return response.statusDetail() == null || response.statusDetail().isBlank()
                ? "Pagamento nao autorizado pelo Mercado Pago."
                : response.statusDetail();
    }

    private static boolean isTimeout(Throwable failure) {
        var current = failure;
        while (current != null) {
            var message = current.getMessage();
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof TimeoutException
                    || (message != null && message.toLowerCase(Locale.ROOT).contains("timeout"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
