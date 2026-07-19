package br.com.oficina.billing.framework.payments;

import br.com.oficina.billing.core.entities.InstrucoesPix;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class MercadoPagoPagamentoGateway implements PagamentoGateway {
    private static final String PROVEDOR = "mercado-pago";
    private static final String TIMEOUT_REASON = "timeout";
    private static final String PIX = "pix";
    private static final String BANK_TRANSFER = "bank_transfer";

    private final MercadoPagoClient paymentClient;
    private final MercadoPagoQueryClient paymentQueryClient;
    private final MercadoPagoOrderClient orderClient;
    private final MercadoPagoMetrics metrics;
    private final boolean enabled;
    private final Optional<String> accessToken;
    private final String payerEmail;
    private final Optional<String> payerFirstName;
    private final ApiMode apiMode;

    public MercadoPagoPagamentoGateway(
            MercadoPagoClient client,
            MercadoPagoMetrics metrics,
            boolean enabled,
            Optional<String> accessToken,
            String payerEmail) {
        this(
                client,
                unsupportedPaymentQueryClient(),
                unsupportedOrderClient(),
                metrics,
                enabled,
                accessToken,
                payerEmail,
                Optional.empty(),
                ApiMode.PAYMENTS,
                "test");
    }

    public MercadoPagoPagamentoGateway(
            MercadoPagoClient client,
            MercadoPagoQueryClient queryClient,
            MercadoPagoMetrics metrics,
            boolean enabled,
            Optional<String> accessToken,
            String payerEmail) {
        this(
                client,
                queryClient,
                unsupportedOrderClient(),
                metrics,
                enabled,
                accessToken,
                payerEmail,
                Optional.empty(),
                ApiMode.PAYMENTS,
                "test");
    }

    @Inject
    public MercadoPagoPagamentoGateway(
            @RestClient MercadoPagoClient paymentClient,
            @RestClient MercadoPagoQueryClient paymentQueryClient,
            @RestClient MercadoPagoOrderClient orderClient,
            MercadoPagoMetrics metrics,
            @ConfigProperty(name = "oficina.mercado-pago.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "oficina.mercado-pago.access-token") Optional<String> accessToken,
            @ConfigProperty(name = "oficina.mercado-pago.payer-email", defaultValue = "cliente.local@oficina.com")
                    String payerEmail,
            @ConfigProperty(name = "oficina.mercado-pago.payer-first-name") Optional<String> payerFirstName,
            @ConfigProperty(name = "oficina.mercado-pago.api-mode", defaultValue = "orders") String apiMode,
            @ConfigProperty(name = "oficina.observability.deployment-environment", defaultValue = "local")
                    String environment) {
        this(
                paymentClient,
                paymentQueryClient,
                orderClient,
                metrics,
                enabled,
                accessToken,
                payerEmail,
                payerFirstName,
                ApiMode.from(apiMode),
                environment);
    }

    MercadoPagoPagamentoGateway(
            MercadoPagoClient paymentClient,
            MercadoPagoQueryClient paymentQueryClient,
            MercadoPagoOrderClient orderClient,
            MercadoPagoMetrics metrics,
            boolean enabled,
            Optional<String> accessToken,
            String payerEmail,
            Optional<String> payerFirstName,
            ApiMode apiMode,
            String environment) {
        this.paymentClient = paymentClient;
        this.paymentQueryClient = paymentQueryClient;
        this.orderClient = orderClient;
        this.metrics = metrics;
        this.enabled = enabled;
        this.accessToken = accessToken;
        this.payerEmail = payerEmail;
        this.payerFirstName = payerFirstName.filter(value -> !value.isBlank());
        this.apiMode = apiMode;
        validarSandbox(environment);
    }

    @Override
    public CompletableFuture<PagamentoGatewayResult> solicitar(Pagamento pagamento) {
        return CompletableFuture.completedFuture(solicitarPagamento(pagamento));
    }

    @Override
    public CompletableFuture<PagamentoGatewayResult> consultar(Pagamento pagamento) {
        return CompletableFuture.completedFuture(consultarPagamento(pagamento));
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
        validarConfiguracao(pagamento);

        return switch (apiMode) {
            case ORDERS -> executarComMetricas(
                    pagamento,
                    "solicitacao da order",
                    () -> criarOrder(pagamento));
            case PAYMENTS -> executarComMetricas(
                    pagamento,
                    "solicitacao de pagamento",
                    () -> criarPayment(pagamento));
        };
    }

    private PagamentoGatewayResult consultarPagamento(Pagamento pagamento) {
        validarConfiguracao(pagamento);
        if (pagamento.tipoReferenciaExterna() == null) {
            metrics.recordFailure(pagamento, "invalid_reference_type", false, null);
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Tipo da referencia externa do Mercado Pago nao informado.");
        }
        return switch (pagamento.tipoReferenciaExterna()) {
            case ORDER -> executarComMetricas(
                    pagamento,
                    "consulta da order",
                    () -> consultarOrder(pagamento));
            case PAYMENT -> executarComMetricas(
                    pagamento,
                    "consulta do pagamento",
                    () -> consultarPayment(pagamento));
        };
    }

    private ResultadoProvedor criarOrder(Pagamento pagamento) {
        var request = new MercadoPagoOrderRequest(
                "online",
                "automatic",
                pagamento.pagamentoId().toString(),
                pagamento.valor(),
                new MercadoPagoOrderRequest.Payer(
                        payerEmail,
                        payerFirstName.orElse(null)),
                new MercadoPagoOrderRequest.Transactions(List.of(
                        new MercadoPagoOrderRequest.Payment(
                                pagamento.valor(),
                                new MercadoPagoOrderRequest.PaymentMethod(PIX, BANK_TRANSFER)))));
        var response = orderClient.createOrder(
                authorization(),
                pagamento.pagamentoId().toString(),
                request);
        return new ResultadoProvedor(toOrderResult(pagamento, response), response.status());
    }

    private ResultadoProvedor consultarOrder(Pagamento pagamento) {
        var response = orderClient.getOrder(authorization(), pagamento.transacaoExternaId());
        return new ResultadoProvedor(toOrderResult(pagamento, response), response.status());
    }

    private ResultadoProvedor criarPayment(Pagamento pagamento) {
        var request = new MercadoPagoPaymentRequest(
                pagamento.valor(),
                "Ordem de servico " + pagamento.ordemServicoId(),
                PIX,
                pagamento.pagamentoId().toString(),
                new MercadoPagoPaymentRequest.Payer(payerEmail));
        var response = paymentClient.createPayment(
                authorization(),
                pagamento.pagamentoId().toString(),
                request);
        return new ResultadoProvedor(toPaymentResult(response), response.status());
    }

    private ResultadoProvedor consultarPayment(Pagamento pagamento) {
        var response = paymentQueryClient.getPayment(authorization(), pagamento.transacaoExternaId());
        return new ResultadoProvedor(toPaymentResult(response), response.status());
    }

    private PagamentoGatewayResult executarComMetricas(
            Pagamento pagamento,
            String operacao,
            Supplier<ResultadoProvedor> chamada) {
        var sample = metrics.startRequest();
        try {
            var response = chamada.get();
            metrics.recordResult(pagamento, response.result(), response.providerStatus(), sample);
            return response.result();
        } catch (WebApplicationException exception) {
            var status = exception.getResponse().getStatus();
            metrics.recordFailure(pagamento, "provider_http_error", status >= 500, sample);
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Mercado Pago recusou a " + operacao + " com HTTP " + status + ".");
        } catch (ProcessingException exception) {
            var reason = isTimeout(exception) ? TIMEOUT_REASON : "communication";
            metrics.recordFailure(pagamento, reason, true, sample);
            throw new BusinessException(
                    "DEPENDENCY_FAILURE",
                    "Falha de comunicacao com Mercado Pago durante a " + operacao + ".");
        } catch (BusinessException exception) {
            metrics.recordFailure(pagamento, "invalid_response", false, sample);
            throw exception;
        }
    }

    private PagamentoGatewayResult toOrderResult(
            Pagamento pagamento,
            MercadoPagoOrderResponse response) {
        var providerPayment = validarOrder(pagamento, response);
        var orderId = response.id().trim();
        var status = normalized(response.status());
        var statusDetail = normalized(response.statusDetail());
        return switch (status) {
            case "created", "processing" -> PagamentoGatewayResult.criado(
                    PROVEDOR,
                    orderId,
                    TipoReferenciaExternaPagamento.ORDER,
                    instrucoesPix(providerPayment));
            case "action_required" -> {
                if (!"waiting_payment".equals(statusDetail) && !"waiting_transfer".equals(statusDetail)) {
                    throw invalidResponse();
                }
                yield PagamentoGatewayResult.criado(
                        PROVEDOR,
                        orderId,
                        TipoReferenciaExternaPagamento.ORDER,
                        instrucoesPix(providerPayment));
            }
            case "processed" -> {
                if (!"accredited".equals(statusDetail)) {
                    throw invalidResponse();
                }
                yield PagamentoGatewayResult.confirmado(
                        PROVEDOR,
                        orderId,
                        TipoReferenciaExternaPagamento.ORDER);
            }
            case "failed", "canceled", "expired", "refunded", "charged_back" ->
                    PagamentoGatewayResult.recusado(
                            PROVEDOR,
                            orderId,
                            TipoReferenciaExternaPagamento.ORDER,
                            detalhe(response.statusDetail()));
            default -> throw invalidResponse();
        };
    }

    private MercadoPagoOrderResponse.Payment validarOrder(
            Pagamento pagamento,
            MercadoPagoOrderResponse response) {
        if (response == null
                || response.id() == null
                || response.id().isBlank()
                || response.status() == null
                || response.status().isBlank()
                || !pagamento.pagamentoId().toString().equals(response.externalReference())
                || response.totalAmount() == null
                || pagamento.valor().compareTo(response.totalAmount()) != 0
                || response.transactions() == null
                || response.transactions().payments() == null
                || response.transactions().payments().size() != 1) {
            throw invalidResponse();
        }
        var providerPayment = response.transactions().payments().getFirst();
        if (providerPayment == null
                || providerPayment.amount() == null
                || pagamento.valor().compareTo(providerPayment.amount()) != 0
                || providerPayment.paymentMethod() == null
                || !PIX.equalsIgnoreCase(providerPayment.paymentMethod().id())
                || !BANK_TRANSFER.equalsIgnoreCase(providerPayment.paymentMethod().type())) {
            throw invalidResponse();
        }
        return providerPayment;
    }

    private PagamentoGatewayResult toPaymentResult(MercadoPagoPaymentResponse response) {
        if (response == null || response.id() == null || response.status() == null || response.status().isBlank()) {
            throw invalidResponse();
        }

        var transacaoExternaId = response.id().toString();
        var status = normalized(response.status());
        return switch (status) {
            case "approved" -> PagamentoGatewayResult.confirmado(
                    PROVEDOR,
                    transacaoExternaId,
                    TipoReferenciaExternaPagamento.PAYMENT);
            case "rejected", "cancelled", "refunded", "charged_back" ->
                    PagamentoGatewayResult.recusado(
                            PROVEDOR,
                            transacaoExternaId,
                            TipoReferenciaExternaPagamento.PAYMENT,
                            detalhe(response.statusDetail()));
            default -> PagamentoGatewayResult.criado(
                    PROVEDOR,
                    transacaoExternaId,
                    TipoReferenciaExternaPagamento.PAYMENT,
                    instrucoesPix(response));
        };
    }

    private InstrucoesPix instrucoesPix(MercadoPagoOrderResponse.Payment payment) {
        var method = payment.paymentMethod();
        if (method.qrCode() == null || method.qrCode().isBlank()) {
            return null;
        }
        return new InstrucoesPix(
                method.qrCode(),
                method.qrCodeBase64(),
                method.ticketUrl(),
                null);
    }

    private InstrucoesPix instrucoesPix(MercadoPagoPaymentResponse response) {
        if (response.pointOfInteraction() == null || response.pointOfInteraction().transactionData() == null) {
            return null;
        }
        var data = response.pointOfInteraction().transactionData();
        if (data.qrCode() == null || data.qrCode().isBlank()) {
            return null;
        }
        return new InstrucoesPix(
                data.qrCode(),
                data.qrCodeBase64(),
                data.ticketUrl(),
                response.dateOfExpiration());
    }

    private void validarConfiguracao(Pagamento pagamento) {
        if (!enabled || accessToken.isEmpty() || accessToken.get().isBlank()) {
            metrics.recordFailure(pagamento, "configuration", true, null);
            throw new BusinessException(
                    "DEPENDENCY_UNAVAILABLE",
                    "Integracao com Mercado Pago nao esta configurada.");
        }
    }

    private void validarSandbox(String environment) {
        if (payerFirstName.filter("APRO"::equalsIgnoreCase).isEmpty()) {
            return;
        }
        var normalizedEnvironment = normalized(environment);
        if (!"lab".equals(normalizedEnvironment) && !"test".equals(normalizedEnvironment)) {
            throw new IllegalArgumentException(
                    "OFICINA_MERCADO_PAGO_PAYER_FIRST_NAME=APRO e permitido apenas em lab ou test.");
        }
    }

    private String authorization() {
        return "Bearer " + accessToken.orElseThrow();
    }

    private String detalhe(String statusDetail) {
        return statusDetail == null || statusDetail.isBlank()
                ? "Pagamento nao autorizado pelo Mercado Pago."
                : statusDetail;
    }

    private BusinessException invalidResponse() {
        return new BusinessException("DEPENDENCY_FAILURE", "Resposta invalida do Mercado Pago.");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isTimeout(Throwable failure) {
        var current = failure;
        while (current != null) {
            var message = current.getMessage();
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof TimeoutException
                    || (message != null && message.toLowerCase(Locale.ROOT).contains(TIMEOUT_REASON))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MercadoPagoQueryClient unsupportedPaymentQueryClient() {
        return (authorization, paymentId) -> {
            throw new UnsupportedOperationException("Consulta de payment nao configurada neste contexto.");
        };
    }

    private static MercadoPagoOrderClient unsupportedOrderClient() {
        return new MercadoPagoOrderClient() {
            @Override
            public MercadoPagoOrderResponse createOrder(
                    String authorization,
                    String idempotencyKey,
                    MercadoPagoOrderRequest request) {
                throw new UnsupportedOperationException("Criacao de order nao configurada neste contexto.");
            }

            @Override
            public MercadoPagoOrderResponse getOrder(String authorization, String orderId) {
                throw new UnsupportedOperationException("Consulta de order nao configurada neste contexto.");
            }
        };
    }

    enum ApiMode {
        ORDERS,
        PAYMENTS;

        static ApiMode from(String value) {
            try {
                return valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "OFICINA_MERCADO_PAGO_API_MODE deve ser orders ou payments.",
                        exception);
            }
        }
    }

    private record ResultadoProvedor(
            PagamentoGatewayResult result,
            String providerStatus) {
    }
}
