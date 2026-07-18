package br.com.oficina.billing.framework.payments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
    void deveRejeitarAssinaturaExpiradaOuConfiguracaoAusente() throws Exception {
        var expired = new MercadoPagoWebhookSignatureValidator(SECRET, 10, CLOCK);
        var dataId = "123456";
        var requestId = "request-1";
        var signature = "ts=" + (TIMESTAMP - 11) + ",v1=" + signature(dataId, requestId, TIMESTAMP - 11);

        assertFalse(expired.isValid(signature, requestId, dataId));
        assertFalse(new MercadoPagoWebhookSignatureValidator("", 300, CLOCK)
                .isValid(signature, requestId, dataId));
    }

    private String signature(String dataId, String requestId) throws Exception {
        return signature(dataId, requestId, TIMESTAMP);
    }

    private String signature(String dataId, String requestId, long timestamp) throws Exception {
        var manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + timestamp + ";";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
    }
}
