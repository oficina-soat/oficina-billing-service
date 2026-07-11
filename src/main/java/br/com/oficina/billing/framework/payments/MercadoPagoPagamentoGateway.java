package br.com.oficina.billing.framework.payments;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.interfaces.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.PagamentoGatewayResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class MercadoPagoPagamentoGateway implements PagamentoGateway {
    private static final String PROVEDOR = "mercado-pago";

    private final MercadoPagoClient client;
    private final boolean enabled;
    private final Optional<String> accessToken;
    private final String payerEmail;

    public MercadoPagoPagamentoGateway(
            @RestClient MercadoPagoClient client,
            @ConfigProperty(name = "oficina.mercado-pago.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "oficina.mercado-pago.access-token") Optional<String> accessToken,
            @ConfigProperty(name = "oficina.mercado-pago.payer-email", defaultValue = "cliente.local@oficina.com") String payerEmail) {
        this.client = client;
        this.enabled = enabled;
        this.accessToken = accessToken;
        this.payerEmail = payerEmail;
    }

    @Override
    public PagamentoGatewayResult solicitar(Pagamento pagamento) {
        if (!enabled) {
            return PagamentoGatewayResult.naoIntegrado();
        }
        if (pagamento.metodo() != MetodoPagamento.PIX) {
            throw new BusinessException(
                    "DEPENDENCY_UNAVAILABLE",
                    "Mercado Pago esta habilitado apenas para pagamentos PIX nesta versao.");
        }
        if (accessToken.isEmpty() || accessToken.get().isBlank()) {
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
        try {
            var response = client.createPayment(
                    "Bearer " + accessToken.get(),
                    pagamento.pagamentoId().toString(),
                    request);
            return toResult(response);
        } catch (WebApplicationException exception) {
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Mercado Pago recusou a solicitacao de pagamento com HTTP "
                            + exception.getResponse().getStatus() + ".");
        } catch (ProcessingException _) {
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Falha de comunicacao com Mercado Pago ao solicitar pagamento.");
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
}
