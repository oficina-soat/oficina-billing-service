package br.com.oficina.billing.framework.web;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 100)
public class MercadoPagoWebhookRawDumpFilter implements ContainerRequestFilter {
    static final String WEBHOOK_PATH = "api/v1/integracoes/mercado-pago/webhooks";
    static final Path DEFAULT_DUMP_PATH = Path.of(
            System.getProperty("user.dir"), ".oficina-diagnostics", "mercado-pago-webhook-request.log");
    private static final int MAX_ENTITY_BYTES = 1_048_576;

    private final boolean enabled;
    private final String environment;
    private final Path dumpPath;

    @Inject
    public MercadoPagoWebhookRawDumpFilter(
            @ConfigProperty(name = "oficina.mercado-pago.webhook-raw-dump-enabled", defaultValue = "false")
                    boolean enabled,
            @ConfigProperty(name = "oficina.observability.deployment-environment", defaultValue = "local")
                    String environment) {
        this(enabled, environment, DEFAULT_DUMP_PATH);
    }

    MercadoPagoWebhookRawDumpFilter(boolean enabled, String environment, Path dumpPath) {
        this.enabled = enabled;
        this.environment = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        this.dumpPath = dumpPath;
        if (enabled && !isNonProductionEnvironment(this.environment)) {
            throw new IllegalStateException("O dump bruto temporario do webhook so pode ser habilitado em lab ou test.");
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!enabled || !isWebhook(requestContext) || Files.exists(dumpPath)) {
            return;
        }
        var entity = requestContext.hasEntity()
                ? requestContext.getEntityStream().readNBytes(MAX_ENTITY_BYTES + 1)
                : new byte[0];
        requestContext.setEntityStream(new ByteArrayInputStream(entity));
        if (entity.length > MAX_ENTITY_BYTES) {
            throw new IOException("Payload do webhook excede o limite do dump temporario.");
        }
        dump(requestContext, entity);
    }

    private void dump(ContainerRequestContext requestContext, byte[] entity) throws IOException {
        createPrivateDirectory();
        var dump = new StringBuilder()
                .append(requestContext.getMethod())
                .append(' ')
                .append(requestContext.getUriInfo().getRequestUri())
                .append('\n');
        requestContext.getHeaders().forEach((name, values) -> values.forEach(value -> dump
                .append(name)
                .append(": ")
                .append(value)
                .append('\n')));
        dump.append('\n').append(new String(entity, StandardCharsets.UTF_8));
        try {
            Files.writeString(
                    dumpPath,
                    dump,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            return;
        }
        Files.setPosixFilePermissions(
                dumpPath, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private void createPrivateDirectory() throws IOException {
        var directory = dumpPath.getParent();
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(
                directory,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
    }

    private boolean isWebhook(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();
        return WEBHOOK_PATH.equals(path) || ("/" + WEBHOOK_PATH).equals(path);
    }

    private boolean isNonProductionEnvironment(String value) {
        return "lab".equals(value) || "test".equals(value);
    }
}
