package br.com.oficina.billing.framework.payments;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoGatewayResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MercadoPagoMetrics {
    static final String ENABLED = "payment.provider.enabled";
    static final String REQUESTS = "payment.provider.requests.count";
    static final String DURATION = "payment.provider.request.duration";
    static final String AMOUNT = "payment.provider.amount";
    static final String FAILURES = "payment.provider.failures.count";
    static final String UNAVAILABLE = "payment.provider.unavailable.count";

    private static final String SERVICE = "oficina-billing-service";
    private static final String PROVIDER = "mercado-pago";
    private static final String CURRENCY = "BRL";
    private static final String NO_PROVIDER_STATUS = "none";
    private static final String TAG_METHOD = "method";
    private static final String TAG_OUTCOME = "outcome";
    private static final Set<String> PROVIDER_STATUSES = Set.of(
            "approved",
            "rejected",
            "cancelled",
            "refunded",
            "charged_back",
            "pending",
            "in_process");

    private final MeterRegistry registry;
    private final Tags commonTags = Tags.of("service", SERVICE, "provider", PROVIDER);
    private final AtomicInteger enabledState;

    public MercadoPagoMetrics(
            MeterRegistry registry,
            @ConfigProperty(name = "oficina.mercado-pago.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "oficina.observability.deployment-environment", defaultValue = "local")
                    String environment) {
        this.registry = registry;
        enabledState = new AtomicInteger(enabled ? 1 : 0);
        Gauge.builder(ENABLED, enabledState, AtomicInteger::get)
                .tags(commonTags.and("environment", normalizedEnvironment(environment)))
                .description("Indica se a integração com o provedor financeiro está habilitada")
                .strongReference(true)
                .register(registry);
    }

    Timer.Sample startRequest() {
        return Timer.start(registry);
    }

    void recordNotIntegrated(Pagamento pagamento) {
        recordRequest(pagamento, "not_integrated", NO_PROVIDER_STATUS, null);
    }

    void recordResult(
            Pagamento pagamento,
            PagamentoGatewayResult result,
            String providerStatus,
            Timer.Sample sample) {
        var outcome = switch (result.status()) {
            case CONFIRMADO -> "confirmed";
            case RECUSADO, CANCELADO -> "rejected";
            case CRIADO -> "pending";
        };
        recordRequest(pagamento, outcome, normalizedProviderStatus(providerStatus), sample);
        if (result.status() == StatusPagamento.RECUSADO) {
            failureCounter(pagamento, "business_rejection").increment();
        }
    }

    void recordFailure(
            Pagamento pagamento,
            String reason,
            boolean unavailable,
            Timer.Sample sample) {
        recordRequest(pagamento, "failure", NO_PROVIDER_STATUS, sample);
        failureCounter(pagamento, reason).increment();
        if (unavailable) {
            registry.counter(UNAVAILABLE, commonTags.and("reason", reason)).increment();
        }
    }

    private void recordRequest(
            Pagamento pagamento,
            String outcome,
            String providerStatus,
            Timer.Sample sample) {
        var method = pagamento.metodo().name();
        registry.counter(
                        REQUESTS,
                        commonTags.and(TAG_METHOD, method, TAG_OUTCOME, outcome, "providerStatus", providerStatus))
                .increment();
        DistributionSummary.builder(AMOUNT)
                .tags(commonTags.and(TAG_METHOD, method, TAG_OUTCOME, outcome, "currency", CURRENCY))
                .baseUnit(CURRENCY)
                .register(registry)
                .record(pagamento.valor().doubleValue());
        if (sample != null) {
            sample.stop(Timer.builder(DURATION)
                    .tags(commonTags.and(TAG_METHOD, method, TAG_OUTCOME, outcome))
                    .publishPercentileHistogram()
                    .register(registry));
        }
    }

    private Counter failureCounter(Pagamento pagamento, String reason) {
        return registry.counter(
                FAILURES,
                commonTags.and(TAG_METHOD, pagamento.metodo().name(), "reason", reason));
    }

    private static String normalizedProviderStatus(String providerStatus) {
        if (providerStatus == null) {
            return NO_PROVIDER_STATUS;
        }
        var normalized = providerStatus.trim().toLowerCase(Locale.ROOT);
        return PROVIDER_STATUSES.contains(normalized) ? normalized : "other";
    }

    private static String normalizedEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "unknown";
        }
        return environment.trim().toLowerCase(Locale.ROOT);
    }
}
