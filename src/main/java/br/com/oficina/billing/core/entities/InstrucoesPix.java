package br.com.oficina.billing.core.entities;

import java.time.OffsetDateTime;

public record InstrucoesPix(
        String copiaECola,
        String qrCodeBase64,
        String ticketUrl,
        OffsetDateTime expiraEm) {
}
