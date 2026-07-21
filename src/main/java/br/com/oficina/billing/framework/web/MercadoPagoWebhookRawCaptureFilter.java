package br.com.oficina.billing.framework.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 100)
public class MercadoPagoWebhookRawCaptureFilter implements ContainerRequestFilter {
    static final String WEBHOOK_PATH = "api/v1/integracoes/mercado-pago/webhooks";
    static final Path DEFAULT_CAPTURE_PATH = Path.of("/tmp/mercado-pago-webhook-request.json");
    private static final int MAX_ENTITY_BYTES = 1_048_576;

    private final boolean enabled;
    private final String environment;
    private final Path capturePath;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Inject
    public MercadoPagoWebhookRawCaptureFilter(
            @ConfigProperty(name = "oficina.mercado-pago.webhook-raw-capture-enabled", defaultValue = "false")
                    boolean enabled,
            @ConfigProperty(name = "oficina.observability.deployment-environment", defaultValue = "local")
                    String environment,
            ObjectMapper objectMapper) {
        this(enabled, environment, DEFAULT_CAPTURE_PATH, objectMapper, Clock.systemUTC());
    }

    MercadoPagoWebhookRawCaptureFilter(
            boolean enabled,
            String environment,
            Path capturePath,
            ObjectMapper objectMapper,
            Clock clock) {
        this.enabled = enabled;
        this.environment = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        this.capturePath = capturePath;
        this.objectMapper = objectMapper;
        this.clock = clock;
        if (enabled && !isNonProductionEnvironment(this.environment)) {
            throw new IllegalStateException(
                    "A captura bruta temporaria do webhook so pode ser habilitada em lab ou test.");
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!enabled || !isWebhook(requestContext) || Files.exists(capturePath)) {
            return;
        }
        var entity = requestContext.hasEntity()
                ? requestContext.getEntityStream().readNBytes(MAX_ENTITY_BYTES + 1)
                : new byte[0];
        requestContext.setEntityStream(new ByteArrayInputStream(entity));
        if (entity.length > MAX_ENTITY_BYTES) {
            throw new IOException("Payload do webhook excede o limite da captura temporaria.");
        }
        capture(requestContext, entity);
    }

    private void capture(ContainerRequestContext requestContext, byte[] entity) throws IOException {
        var capturedRequest = new LinkedHashMap<String, Object>();
        capturedRequest.put("capturedAt", Instant.now(clock).toString());
        capturedRequest.put("environment", environment);
        capturedRequest.put("method", requestContext.getMethod());
        capturedRequest.put("requestUri", requestContext.getUriInfo().getRequestUri().toString());
        capturedRequest.put("headers", requestContext.getHeaders());
        capturedRequest.put("body", new String(entity, java.nio.charset.StandardCharsets.UTF_8));

        try {
            Files.writeString(
                    capturePath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(capturedRequest),
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            return;
        }
        Files.setPosixFilePermissions(
                capturePath,
                java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private boolean isWebhook(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();
        return WEBHOOK_PATH.equals(path) || ("/" + WEBHOOK_PATH).equals(path);
    }

    private boolean isNonProductionEnvironment(String value) {
        return "lab".equals(value) || "test".equals(value);
    }
}
