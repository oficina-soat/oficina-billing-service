package br.com.oficina.billing.framework.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class IdempotencyRecordTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-12T12:00:00Z");

    @Test
    void deveValidarCamposObrigatorios() {
        assertInvalido("Escopo de idempotencia e obrigatorio.", () -> idempotencyRecord(null, "key", "hash", ProcessingStatus.PROCESSING, "corr", NOW, NOW, NOW));
        assertInvalido("Escopo de idempotencia e obrigatorio.", () -> idempotencyRecord(" ", "key", "hash", ProcessingStatus.PROCESSING, "corr", NOW, NOW, NOW));
        assertInvalido("Chave de idempotencia e obrigatoria.", () -> idempotencyRecord("scope", null, "hash", ProcessingStatus.PROCESSING, "corr", NOW, NOW, NOW));
        assertInvalido("Chave de idempotencia e obrigatoria.", () -> idempotencyRecord("scope", " ", "hash", ProcessingStatus.PROCESSING, "corr", NOW, NOW, NOW));
        assertInvalido("Hash da requisicao idempotente e obrigatorio.", () -> idempotencyRecord("scope", "key", null, ProcessingStatus.PROCESSING, "corr", NOW, NOW, NOW));
        assertInvalido("Hash da requisicao idempotente e obrigatorio.", () -> idempotencyRecord("scope", "key", " ", ProcessingStatus.PROCESSING, "corr", NOW, NOW, NOW));
        assertInvalido("Status da requisicao idempotente e obrigatorio.", () -> idempotencyRecord("scope", "key", "hash", null, "corr", NOW, NOW, NOW));
        assertInvalido("CorrelationId da requisicao idempotente e obrigatorio.", () -> idempotencyRecord("scope", "key", "hash", ProcessingStatus.PROCESSING, null, NOW, NOW, NOW));
        assertInvalido("CorrelationId da requisicao idempotente e obrigatorio.", () -> idempotencyRecord("scope", "key", "hash", ProcessingStatus.PROCESSING, " ", NOW, NOW, NOW));
        assertInvalido("Datas da requisicao idempotente sao obrigatorias.", () -> idempotencyRecord("scope", "key", "hash", ProcessingStatus.PROCESSING, "corr", null, NOW, NOW));
        assertInvalido("Datas da requisicao idempotente sao obrigatorias.", () -> idempotencyRecord("scope", "key", "hash", ProcessingStatus.PROCESSING, "corr", NOW, null, NOW));
        assertInvalido("Datas da requisicao idempotente sao obrigatorias.", () -> idempotencyRecord("scope", "key", "hash", ProcessingStatus.PROCESSING, "corr", NOW, NOW, null));
    }

    @Test
    void deveCriarRegistroValido() {
        var idempotencyRecord = idempotencyRecord("scope", "key", "hash", ProcessingStatus.COMPLETED, "corr", NOW, NOW, NOW.plusDays(1));

        assertEquals("scope", idempotencyRecord.scope());
        assertEquals(ProcessingStatus.COMPLETED, idempotencyRecord.processingStatus());
    }

    private static IdempotencyRecord idempotencyRecord(
            String scope,
            String key,
            String requestHash,
            ProcessingStatus status,
            String correlationId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime expiresAt) {
        return new IdempotencyRecord(
                scope,
                key,
                requestHash,
                status,
                null,
                null,
                correlationId,
                "request-id",
                createdAt,
                updatedAt,
                expiresAt);
    }

    private static void assertInvalido(String mensagem, Executable executable) {
        var exception = assertThrows(IllegalArgumentException.class, executable);
        assertEquals(mensagem, exception.getMessage());
    }
}
