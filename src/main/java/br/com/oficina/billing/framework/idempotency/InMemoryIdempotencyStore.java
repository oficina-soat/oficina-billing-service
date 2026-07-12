package br.com.oficina.billing.framework.idempotency;

import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        return Optional.ofNullable(records.get(recordKey(scope, key)));
    }

    @Override
    public IdempotencyRecord createProcessing(
            String scope,
            String key,
            String requestHash,
            String correlationId,
            String requestId,
            OffsetDateTime expiresAt) {
        var agora = OffsetDateTime.now(ZoneOffset.UTC);
        var record = new IdempotencyRecord(
                scope,
                key,
                requestHash,
                ProcessingStatus.PROCESSING,
                null,
                null,
                correlationId,
                requestId,
                agora,
                agora,
                expiresAt);
        return records.computeIfAbsent(recordKey(scope, key), ignored -> record);
    }

    @Override
    public void complete(
            String scope,
            String key,
            ProcessingStatus processingStatus,
            int responseStatus,
            String responseBody) {
        records.computeIfPresent(recordKey(scope, key), (ignored, current) -> new IdempotencyRecord(
                current.scope(),
                current.key(),
                current.requestHash(),
                processingStatus,
                responseStatus,
                responseBody,
                current.correlationId(),
                current.requestId(),
                current.createdAt(),
                OffsetDateTime.now(ZoneOffset.UTC),
                current.expiresAt()));
    }

    private String recordKey(String scope, String key) {
        return scope + "\n" + key;
    }
}
