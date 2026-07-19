package br.com.oficina.billing.framework.payments;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record MercadoPagoOrderResponse(
        String id,
        String status,
        @JsonProperty("status_detail") String statusDetail,
        @JsonProperty("external_reference") String externalReference,
        @JsonProperty("total_amount") BigDecimal totalAmount,
        Transactions transactions) {
    public record Transactions(List<Payment> payments) {
    }

    public record Payment(
            String id,
            BigDecimal amount,
            String status,
            @JsonProperty("status_detail") String statusDetail,
            @JsonProperty("payment_method") PaymentMethod paymentMethod) {
    }

    public record PaymentMethod(
            String id,
            String type,
            @JsonProperty("ticket_url") String ticketUrl,
            @JsonProperty("qr_code") String qrCode,
            @JsonProperty("qr_code_base64") String qrCodeBase64) {
    }
}
