package br.com.oficina.billing.framework.payments;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record MercadoPagoOrderRequest(
        String type,
        @JsonProperty("processing_mode") String processingMode,
        @JsonProperty("external_reference") String externalReference,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonProperty("total_amount") BigDecimal totalAmount,
        Payer payer,
        Transactions transactions) {
    public record Payer(
            String email,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("first_name")
            String firstName) {
    }

    public record Transactions(List<Payment> payments) {
    }

    public record Payment(
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            BigDecimal amount,
            @JsonProperty("payment_method") PaymentMethod paymentMethod) {
    }

    public record PaymentMethod(String id, String type) {
    }
}
