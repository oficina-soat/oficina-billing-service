package br.com.oficina.billing.framework.idempotency;

import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface IdempotencyStore {
    Optional<IdempotencyRecord> find(String scope, String key);

    IdempotencyRecord createProcessing(
            String scope,
            String key,
            String requestHash,
            String correlationId,
            String requestId,
            OffsetDateTime expiresAt);

    void complete(
            String scope,
            String key,
            ProcessingStatus processingStatus,
            int responseStatus,
            String responseBody);
}
