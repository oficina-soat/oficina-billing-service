package br.com.oficina.billing.framework.payments;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record MercadoPagoPaymentRequest(
        @JsonProperty("transaction_amount") BigDecimal transactionAmount,
        String description,
        @JsonProperty("payment_method_id") String paymentMethodId,
        @JsonProperty("external_reference") String externalReference,
        Payer payer) {
    public record Payer(String email) {
    }
}
