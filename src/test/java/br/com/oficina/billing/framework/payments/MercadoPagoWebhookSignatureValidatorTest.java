package br.com.oficina.billing.framework.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MercadoPagoWebhookSignatureValidatorTest {
    private static final String SECRET = "webhook-test-secret";
    private static final long TIMESTAMP = 1_767_268_800L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP), ZoneOffset.UTC);

    @Test
    void deveValidarManifestoAssinadoDentroDaJanela() throws Exception {
        var validator = new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK);
        var dataId = "123456";
        var requestId = "request-1";
        var signature = "ts=" + TIMESTAMP + ",v1=" + signature(dataId, requestId);

        assertTrue(validator.isValid(signature, requestId, dataId));
        assertFalse(validator.isValid(signature, requestId, "outro-id"));
    }

    @Test
    void deveValidarManifestoOrdersPreservandoIdETimestampEmMilissegundos() throws Exception {
        var validator = new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK);
        var dataId = "ORD01JQ4S4KY8HWQ6NA5PXB65B3D3";
        var requestId = "request-orders-1";
        var timestampMillis = TIMESTAMP * 1_000 + 123;
        var signature = "ts=" + timestampMillis + ",v1="
                + signature(dataId, requestId, timestampMillis);
        var signatureWithLowercaseId = "ts=" + timestampMillis + ",v1="
                + signature(dataId.toLowerCase(java.util.Locale.ROOT), requestId, timestampMillis);

        assertTrue(validator.isValid(signature, requestId, dataId));
        assertFalse(validator.isValid(signatureWithLowercaseId, requestId, dataId));
        assertFalse(validator.isValid(signature, requestId, "outra-order"));
    }

    @Test
    void deveValidarManifestoSemDataIdQuandoQueryNaoFoiEnviada() throws Exception {
        var validator = new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK);
        var requestId = "request-without-query-data-id";
        var signature = "ts=" + TIMESTAMP + ",v1=" + signature(null, requestId);

        assertTrue(validator.isValid(signature, requestId, null));
        assertTrue(validator.isValid(signature, requestId, " "));
        assertFalse(validator.isValid(signature, requestId, "body-id-nao-pertence-ao-manifesto"));
    }

    @Test
    void deveRejeitarAssinaturaExpiradaOuConfiguracaoAusente() throws Exception {
        var expired = new MercadoPagoWebhookSignatureValidator(SECRET, 10, CLOCK);
        var dataId = "123456";
        var requestId = "request-1";
        var signature = "ts=" + (TIMESTAMP - 11) + ",v1=" + signature(dataId, requestId, TIMESTAMP - 11);

        assertFalse(expired.isValid(signature, requestId, dataId));
        assertFalse(new MercadoPagoWebhookSignatureValidator("", 300, CLOCK)
                .isValid(signature, requestId, dataId));
    }

    @Test
    void deveRejeitarTimestampEmMilissegundosForaDaJanela() throws Exception {
        var validator = new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK);
        var dataId = "ORD01JQ4S4KY8HWQ6NA5PXB65B3D3";
        var requestId = "request-orders-expired";
        var timestampMillis = (TIMESTAMP - 301) * 1_000;
        var signature = "ts=" + timestampMillis + ",v1="
                + signature(dataId, requestId, timestampMillis);

        assertFalse(validator.isValid(signature, requestId, dataId));
    }

    @Test
    void deveRejeitarCamposObrigatoriosAusentes() {
        var validator = new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK);

        assertFalse(new MercadoPagoWebhookSignatureValidator(null, 300, CLOCK)
                .isValid("signature", "request-1", "123456"));
        assertFalse(validator.isValid("signature", null, "123456"));
        assertFalse(validator.isValid("signature", " ", "123456"));
    }

    @Test
    void deveRejeitarAssinaturaAusenteOuMalformada() {
        var validator = new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK);

        assertFalse(validator.isValid(null, "request-1", "123456"));
        assertFalse(validator.isValid(" ", "request-1", "123456"));
        assertFalse(validator.isValid("invalid", "request-1", "123456"));
        assertFalse(validator.isValid("ts=not-a-number,v1=hash", "request-1", "123456"));
        assertFalse(validator.isValid("ts=" + TIMESTAMP, "request-1", "123456"));
        assertFalse(validator.isValid("ts=" + TIMESTAMP + ",v1=", "request-1", "123456"));
    }

    @Test
    void devePreservarPrimeiroComponenteDuplicadoEExigirToleranciaPositiva() throws Exception {
        var dataId = "123456";
        var requestId = "request-1";
        var hash = signature(dataId, requestId);
        var duplicateTimestamp = "ts=" + TIMESTAMP + ",ts=0,v1=" + hash;

        assertTrue(new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK)
                .isValid(duplicateTimestamp, requestId, dataId));
        assertFalse(new MercadoPagoWebhookSignatureValidator(SECRET, 0, CLOCK)
                .isValid("ts=" + TIMESTAMP + ",v1=" + hash, requestId, dataId));
    }

    @Test
    void deveSanitizarDiagnosticoSemExporEntradasOuComponenteDesconhecido() {
        var validator = new MercadoPagoWebhookSignatureValidator(SECRET, 300, CLOCK, "lab");
        var requestId = "request-id-sensivel";
        var dataId = "ORD01JQ4S4KY8HWQ6NA5PXB65B3D3";
        var manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + TIMESTAMP + ";";

        var fields = validator.diagnosticFields(
                "rejected",
                "hash_mismatch",
                requestId,
                dataId,
                TIMESTAMP,
                manifest,
                Map.of("ts", Long.toString(TIMESTAMP), "v1", "hash", "segredo-injetado", "valor"));
        var serialized = fields.toString();

        assertEquals("lab", fields.get("webhookEnvironment"));
        assertEquals("ts,v1", fields.get("webhookSignatureComponentNames"));
        assertEquals("uppercase_alphanumeric", fields.get("webhookDataIdFormat"));
        assertFalse(serialized.contains(requestId));
        assertFalse(serialized.contains(dataId));
        assertFalse(serialized.contains(manifest));
        assertFalse(serialized.contains("segredo-injetado"));
    }

    private String signature(String dataId, String requestId) throws Exception {
        return signature(dataId, requestId, TIMESTAMP);
    }

    private String signature(String dataId, String requestId, long timestamp) throws Exception {
        var manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId).append(';');
        }
        manifest.append("request-id:").append(requestId).append(";ts:").append(timestamp).append(';');
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
