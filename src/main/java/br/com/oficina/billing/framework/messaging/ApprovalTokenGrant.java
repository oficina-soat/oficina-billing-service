package br.com.oficina.billing.framework.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApprovalTokenGrant(
        UUID ordemServicoId,
        UUID orcamentoId,
        String action,
        OffsetDateTime expiresAt,
        OffsetDateTime usedAt) {
    public boolean disponivelEm(OffsetDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
