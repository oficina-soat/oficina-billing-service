package br.com.oficina.billing.framework.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApprovalTokenRecord(
        UUID tokenId,
        String tokenHash,
        String action,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {
}
