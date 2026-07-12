package br.com.oficina.billing.framework.idempotency;

import java.time.OffsetDateTime;

public record IdempotencyRecord(
        String scope,
        String key,
        String requestHash,
        ProcessingStatus processingStatus,
        Integer responseStatus,
        String responseBody,
        String correlationId,
        String requestId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime expiresAt) {

    public enum ProcessingStatus {
        PROCESSING,
        COMPLETED,
        FAILED_RETRYABLE,
        FAILED_FINAL
    }

    public IdempotencyRecord {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Escopo de idempotencia e obrigatorio.");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Chave de idempotencia e obrigatoria.");
        }
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("Hash da requisicao idempotente e obrigatorio.");
        }
        if (processingStatus == null) {
            throw new IllegalArgumentException("Status da requisicao idempotente e obrigatorio.");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("CorrelationId da requisicao idempotente e obrigatorio.");
        }
        if (createdAt == null || updatedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("Datas da requisicao idempotente sao obrigatorias.");
        }
    }
}
