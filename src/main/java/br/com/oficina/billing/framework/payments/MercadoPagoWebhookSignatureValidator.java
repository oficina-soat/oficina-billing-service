package br.com.oficina.billing.framework.payments;

import br.com.oficina.billing.framework.observability.StructuredLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MercadoPagoWebhookSignatureValidator {
    private static final Logger LOG = Logger.getLogger(MercadoPagoWebhookSignatureValidator.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final long MILLISECOND_EPOCH_THRESHOLD = 1_000_000_000_000L;

    private final String secret;
    private final long toleranceSeconds;
    private final Clock clock;

    @Inject
    public MercadoPagoWebhookSignatureValidator(
            @ConfigProperty(name = "oficina.mercado-pago.webhook-secret") Optional<String> secret,
            @ConfigProperty(name = "oficina.mercado-pago.webhook-tolerance-seconds", defaultValue = "300")
                    long toleranceSeconds) {
        this(secret.orElse(""), toleranceSeconds, Clock.systemUTC());
    }

    MercadoPagoWebhookSignatureValidator(String secret, long toleranceSeconds, Clock clock) {
        this.secret = secret;
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    public boolean isValid(String signature, String requestId, String dataId) {
        if (secret == null || secret.isBlank()) {
            return reject("secret_missing");
        }
        if (requestId == null || requestId.isBlank()) {
            return reject("request_id_missing");
        }
        if (signature == null || signature.isBlank()) {
            return reject("signature_missing");
        }
        var components = components(signature);
        var timestamp = parseTimestamp(components.get("ts"));
        var suppliedHash = components.get("v1");
        if (timestamp == null) {
            return reject("timestamp_invalid");
        }
        if (suppliedHash == null || suppliedHash.isBlank()) {
            return reject("hash_missing");
        }
        if (expired(timestamp)) {
            return reject("timestamp_expired");
        }
        var manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:")
                    .append(dataId.toLowerCase(Locale.ROOT))
                    .append(';');
        }
        manifest.append("request-id:")
                .append(requestId)
                .append(";ts:")
                .append(timestamp)
                .append(';');
        var expectedHash = hmac(manifest.toString());
        var valid = MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                suppliedHash.getBytes(StandardCharsets.US_ASCII));
        return valid || reject("hash_mismatch");
    }

    private Map<String, String> components(String signature) {
        if (signature == null || signature.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(signature.split(","))
                .map(String::trim)
                .map(value -> value.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1], (first, _) -> first));
    }

    private Long parseTimestamp(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private boolean expired(long timestamp) {
        var now = Instant.now(clock).getEpochSecond();
        var timestampSeconds = timestamp >= MILLISECOND_EPOCH_THRESHOLD
                ? timestamp / 1_000
                : timestamp;
        return toleranceSeconds < 1 || Math.abs(now - timestampSeconds) > toleranceSeconds;
    }

    private String hmac(String manifest) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Nao foi possivel validar assinatura do webhook.", exception);
        }
    }

    private boolean reject(String reason) {
        StructuredLog.withFields(
                Map.of("webhookValidationReason", reason),
                () -> LOG.warn("mercado pago webhook signature rejected"));
        return false;
    }
}
