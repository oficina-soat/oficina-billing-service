package br.com.oficina.billing.framework.payments;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MercadoPagoPaymentResponse(
        Long id,
        String status,
        @JsonProperty("status_detail") String statusDetail) {
}
