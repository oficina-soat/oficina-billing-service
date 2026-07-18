package br.com.oficina.billing.framework.payments;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record MercadoPagoPaymentResponse(
        Long id,
        String status,
        @JsonProperty("status_detail") String statusDetail,
        @JsonProperty("date_of_expiration") OffsetDateTime dateOfExpiration,
        @JsonProperty("point_of_interaction") PointOfInteraction pointOfInteraction) {
    public MercadoPagoPaymentResponse(Long id, String status, String statusDetail) {
        this(id, status, statusDetail, null, null);
    }

    public record PointOfInteraction(
            @JsonProperty("transaction_data") TransactionData transactionData) {
    }

    public record TransactionData(
            @JsonProperty("qr_code") String qrCode,
            @JsonProperty("qr_code_base64") String qrCodeBase64,
            @JsonProperty("ticket_url") String ticketUrl) {
    }
}
