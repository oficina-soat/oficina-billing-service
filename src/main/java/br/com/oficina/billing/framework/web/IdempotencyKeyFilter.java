package br.com.oficina.billing.framework.web;

import br.com.oficina.billing.framework.idempotency.IdempotencyRecord;
import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import br.com.oficina.billing.framework.idempotency.IdempotencyStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.AUTHORIZATION)
@Blocking
public class IdempotencyKeyFilter implements ContainerRequestFilter, ContainerResponseFilter {
    public static final String HEADER_NAME = "X-Idempotency-Key";
    private static final String LEGACY_HEADER_NAME = "Idempotency-Key";
    private static final String REQUEST_ID_PROPERTY = "requestId";
    private static final String RECORD_PROPERTY = IdempotencyKeyFilter.class.getName() + ".record";
    private static final Set<String> MUTATING_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, "PATCH");

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final String serviceName;

    @Inject
    public IdempotencyKeyFilter(
            IdempotencyStore store,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "quarkus.application.name") String serviceName) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!MUTATING_METHODS.contains(requestContext.getMethod())) {
            return;
        }

        String key = idempotencyKey(requestContext);
        if (key == null) {
            requestContext.abortWith(error(
                    requestContext,
                    Response.Status.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Header X-Idempotency-Key obrigatorio para operacoes mutaveis."));
            return;
        }

        byte[] entity = requestEntity(requestContext);
        String scope = scope(requestContext);
        String requestHash = requestHash(requestContext, scope, entity);
        var existing = store.find(scope, key);
        if (existing.isPresent()) {
            handleExisting(requestContext, existing.get(), requestHash);
            return;
        }

        var correlationId = correlationId(requestContext);
        var requestId = requestId(requestContext);
        var created = store.createProcessing(
                scope,
                key,
                requestHash,
                correlationId,
                requestId,
                expiresAt(requestContext));
        if (!created.requestHash().equals(requestHash) || !requestId.equals(created.requestId())) {
            handleExisting(requestContext, created, requestHash);
            return;
        }
        requestContext.setProperty(RECORD_PROPERTY, created);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        var storedRecord = requestContext.getProperty(RECORD_PROPERTY);
        if (!(storedRecord instanceof IdempotencyRecord idempotencyRecord)) {
            return;
        }
        store.complete(
                idempotencyRecord.scope(),
                idempotencyRecord.key(),
                processingStatus(responseContext.getStatus()),
                responseContext.getStatus(),
                responseBody(responseContext.getEntity()));
    }

    private void handleExisting(
            ContainerRequestContext requestContext,
            IdempotencyRecord existingRecord,
            String requestHash) {
        if (!existingRecord.requestHash().equals(requestHash)) {
            requestContext.abortWith(error(
                    requestContext,
                    Response.Status.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Chave de idempotencia reutilizada com payload divergente."));
            return;
        }
        switch (existingRecord.processingStatus()) {
            case PROCESSING -> requestContext.abortWith(error(
                    requestContext,
                    Response.Status.CONFLICT,
                    "IDEMPOTENCY_IN_PROGRESS",
                    "Requisicao idempotente ainda em processamento."));
            case COMPLETED, FAILED_FINAL -> requestContext.abortWith(replay(existingRecord));
            case FAILED_RETRYABLE -> requestContext.setProperty(RECORD_PROPERTY, existingRecord);
        }
    }

    private String idempotencyKey(ContainerRequestContext requestContext) {
        var key = requestContext.getHeaderString(HEADER_NAME);
        if (key == null || key.isBlank()) {
            key = requestContext.getHeaderString(LEGACY_HEADER_NAME);
        }
        return key == null || key.isBlank() ? null : key.trim();
    }

    private byte[] requestEntity(ContainerRequestContext requestContext) throws IOException {
        byte[] entity = requestContext.hasEntity()
                ? requestContext.getEntityStream().readAllBytes()
                : new byte[0];
        requestContext.setEntityStream(new ByteArrayInputStream(entity));
        return entity;
    }

    private String requestHash(ContainerRequestContext requestContext, String scope, byte[] entity) throws IOException {
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("scope", scope);
        canonical.put("method", requestContext.getMethod());
        canonical.put("path", path(requestContext));
        canonical.put("query", query(requestContext));
        canonical.put("subject", subject(requestContext));
        canonical.put("payload", payload(entity));
        return sha256(canonicalJson(canonical));
    }

    private String canonicalJson(Object value) throws JsonProcessingException {
        return objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(value);
    }

    private Object payload(byte[] entity) throws IOException {
        if (entity.length == 0) {
            return null;
        }
        var raw = new String(entity, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (JsonProcessingException _) {
            return raw;
        }
    }

    private Map<String, List<String>> query(ContainerRequestContext requestContext) {
        var query = new TreeMap<String, List<String>>();
        requestContext.getUriInfo().getQueryParameters().forEach((key, values) ->
                query.put(key, values.stream().sorted().toList()));
        return query;
    }

    private String scope(ContainerRequestContext requestContext) {
        return serviceName + ":" + requestContext.getMethod() + ":" + path(requestContext) + ":" + subject(requestContext);
    }

    private String path(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();
        return path == null || path.isBlank() ? "/" : "/" + path.replaceFirst("^/+", "");
    }

    private String subject(ContainerRequestContext requestContext) {
        var securityContext = requestContext.getSecurityContext();
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return "anonymous";
        }
        return securityContext.getUserPrincipal().getName();
    }

    private String correlationId(ContainerRequestContext requestContext) {
        var correlationId = requestContext.getProperty(CorrelationIdFilter.PROPERTY_NAME);
        if (correlationId == null || correlationId.toString().isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId.toString();
    }

    private String requestId(ContainerRequestContext requestContext) {
        var requestId = requestContext.getProperty(REQUEST_ID_PROPERTY);
        return requestId == null || requestId.toString().isBlank() ? UUID.randomUUID().toString() : requestId.toString();
    }

    private OffsetDateTime expiresAt(ContainerRequestContext requestContext) {
        var path = path(requestContext);
        var days = path.contains("/pagamentos") ? 7 : 1;
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days);
    }

    private ProcessingStatus processingStatus(int responseStatus) {
        if (responseStatus >= 500) {
            return ProcessingStatus.FAILED_RETRYABLE;
        }
        if (responseStatus >= 400) {
            return ProcessingStatus.FAILED_FINAL;
        }
        return ProcessingStatus.COMPLETED;
    }

    private String responseBody(Object entity) throws JsonProcessingException {
        if (entity == null) {
            return null;
        }
        if (entity instanceof String string) {
            return string;
        }
        return objectMapper.writeValueAsString(entity);
    }

    private Response replay(IdempotencyRecord storedRecord) {
        var builder = Response.status(storedRecord.responseStatus() == null ? Response.Status.OK.getStatusCode() : storedRecord.responseStatus())
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIdFilter.HEADER_NAME, storedRecord.correlationId());
        if (storedRecord.responseBody() != null) {
            builder.entity(storedRecord.responseBody());
        }
        return builder.build();
    }

    private Response error(
            ContainerRequestContext requestContext,
            Response.Status status,
            String code,
            String message) {
        var timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        var correlationId = correlationId(requestContext);
        var body = new ErrorResponse(
                timestamp,
                status.getStatusCode(),
                status.getReasonPhrase(),
                code,
                message,
                path(requestContext),
                correlationId,
                requestId(requestContext),
                null,
                null,
                serviceName,
                serviceName + "/" + timestamp.toLocalDate() + "/" + correlationId,
                List.of());
        var response = Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIdFilter.HEADER_NAME, correlationId);
        if ("IDEMPOTENCY_IN_PROGRESS".equals(code)) {
            response.header("Retry-After", "1");
        }
        return response.entity(body).build();
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel para idempotencia.", exception);
        }
    }
}
