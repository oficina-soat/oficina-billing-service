package br.com.oficina.billing.framework.payments;

import br.com.oficina.billing.framework.observability.StructuredLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
    private final String environment;

    @Inject
    public MercadoPagoWebhookSignatureValidator(
            @ConfigProperty(name = "oficina.mercado-pago.webhook-secret") Optional<String> secret,
            @ConfigProperty(name = "oficina.mercado-pago.webhook-tolerance-seconds", defaultValue = "300")
                    long toleranceSeconds,
            @ConfigProperty(name = "oficina.observability.deployment-environment", defaultValue = "local")
                    String environment) {
        this(secret.orElse(""), toleranceSeconds, Clock.systemUTC(), environment);
    }

    MercadoPagoWebhookSignatureValidator(String secret, long toleranceSeconds, Clock clock) {
        this(secret, toleranceSeconds, clock, "test");
    }

    MercadoPagoWebhookSignatureValidator(String secret, long toleranceSeconds, Clock clock, String environment) {
        this.secret = secret;
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
        this.environment = environment == null || environment.isBlank() ? "unknown" : environment.trim();
    }

    public boolean isValid(String signature, String requestId, String dataId) {
        if (secret == null || secret.isBlank()) {
            return reject("secret_missing", requestId, dataId, null, null, components(signature));
        }
        if (requestId == null || requestId.isBlank()) {
            return reject("request_id_missing", requestId, dataId, null, null, components(signature));
        }
        if (signature == null || signature.isBlank()) {
            return reject("signature_missing", requestId, dataId, null, null, Map.of());
        }
        var components = components(signature);
        var timestamp = parseTimestamp(components.get("ts"));
        var suppliedHash = components.get("v1");
        if (timestamp == null) {
            return reject("timestamp_invalid", requestId, dataId, null, null, components);
        }
        if (suppliedHash == null || suppliedHash.isBlank()) {
            return reject("hash_missing", requestId, dataId, timestamp, null, components);
        }
        if (expired(timestamp)) {
            return reject("timestamp_expired", requestId, dataId, timestamp, null, components);
        }
        var manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:")
                    .append(dataId)
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
        if (!valid) {
            return reject("hash_mismatch", requestId, dataId, timestamp, manifest.toString(), components);
        }
        audit("accepted", "hash_comparison", requestId, dataId, timestamp, manifest.toString(), components);
        return true;
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

    private boolean reject(
            String reason,
            String requestId,
            String dataId,
            Long timestamp,
            String manifest,
            Map<String, String> signatureComponents) {
        audit("rejected", reason, requestId, dataId, timestamp, manifest, signatureComponents);
        return false;
    }

    private void audit(
            String result,
            String stage,
            String requestId,
            String dataId,
            Long timestamp,
            String manifest,
            Map<String, String> signatureComponents) {
        var fields = diagnosticFields(result, stage, requestId, dataId, timestamp, manifest, signatureComponents);
        StructuredLog.withFields(
                fields,
                () -> {
                    if ("accepted".equals(result)) {
                        LOG.info("mercado pago webhook signature validated");
                    } else {
                        LOG.warn("mercado pago webhook signature rejected");
                    }
                });
    }

    Map<String, Object> diagnosticFields(
            String result,
            String stage,
            String requestId,
            String dataId,
            Long timestamp,
            String manifest,
            Map<String, String> signatureComponents) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("webhookValidationResult", result);
        fields.put("webhookValidationStage", stage);
        if ("rejected".equals(result)) {
            fields.put("webhookValidationReason", stage);
        }
        fields.put("webhookEnvironment", environment);
        fields.put("webhookRequestIdFingerprint", fingerprint(requestId));
        fields.put("webhookDataIdFingerprint", fingerprint(dataId));
        fields.put("webhookDataIdFormat", dataIdFormat(dataId));
        fields.put("webhookDataIdLength", dataId == null ? 0 : dataId.length());
        fields.put("webhookSignatureTimestamp", timestamp == null ? "missing" : timestamp);
        fields.put("webhookSignatureComponentNames", signatureComponents.keySet().stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .filter(key -> "ts".equals(key) || key.matches("v\\d+"))
                .sorted()
                .collect(Collectors.joining(",")));
        fields.put("webhookManifestFingerprint", fingerprint(manifest));
        fields.put("webhookManifestIncludesDataId", dataId != null && !dataId.isBlank());
        return Map.copyOf(fields);
    }

    private String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel para diagnostico do webhook.", exception);
        }
    }

    private String dataIdFormat(String dataId) {
        if (dataId == null || dataId.isBlank()) {
            return "missing";
        }
        if (dataId.chars().allMatch(Character::isDigit)) {
            return "numeric";
        }
        if (dataId.equals(dataId.toUpperCase(Locale.ROOT))) {
            return "uppercase_alphanumeric";
        }
        if (dataId.equals(dataId.toLowerCase(Locale.ROOT))) {
            return "lowercase_alphanumeric";
        }
        return "mixed_alphanumeric";
    }
}
