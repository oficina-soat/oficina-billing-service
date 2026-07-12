package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.framework.idempotency.IdempotencyRecord;
import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import br.com.oficina.billing.framework.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdempotencyKeyFilterTest {
    private final FakeStore store = new FakeStore();
    private final IdempotencyKeyFilter filter = new IdempotencyKeyFilter(
            store,
            new ObjectMapper(),
            "oficina-billing-service");

    @Test
    void deveIgnorarMetodoNaoMutavel() throws Exception {
        var request = RequestContextStub.get("orcamentos");

        filter.filter(request.proxy());

        assertNull(request.aborted);
        assertFalse(store.created);
    }

    @Test
    void deveExigirChaveParaMetodoMutavel() throws Exception {
        var request = RequestContextStub.post("orcamentos");

        filter.filter(request.proxy());

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), request.aborted.getStatus());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", assertInstanceOf(ErrorResponse.class, request.aborted.getEntity()).code());
    }

    @Test
    void deveCriarRegistroECompletarRespostaSerializada() throws Exception {
        var request = RequestContextStub.post("pagamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, " chave-billing ")
                .property(CorrelationIdFilter.PROPERTY_NAME, "corr-billing")
                .property("requestId", "req-billing")
                .principal("usuario-billing")
                .query("b", "2")
                .query("b", "1")
                .entity("{\"b\":2,\"a\":1}");

        filter.filter(request.proxy());
        filter.filter(request.proxy(), ResponseContextStub.of(201, Map.of("ok", true)));

        var idempotencyRecord = request.idempotencyRecord();
        assertNull(request.aborted);
        assertEquals("chave-billing", idempotencyRecord.key());
        assertTrue(idempotencyRecord.scope().contains("/pagamentos"));
        assertEquals("corr-billing", idempotencyRecord.correlationId());
        assertEquals("req-billing", idempotencyRecord.requestId());
        assertEquals(ProcessingStatus.COMPLETED, store.completion.processingStatus);
        assertEquals(201, store.completion.responseStatus);
        assertTrue(store.completion.responseBody.contains("\"ok\":true"));
        assertEquals("{\"b\":2,\"a\":1}", new String(request.entityStream.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void deveReproduzirRespostaConcluida() throws Exception {
        var request = RequestContextStub.post("pagamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "replay")
                .entity("{\"a\":1}");
        filter.filter(request.proxy());
        var saved = request.idempotencyRecord();
        store.existing = idempotencyRecord(saved, ProcessingStatus.COMPLETED, 202, "{\"status\":\"ok\"}");

        var replay = RequestContextStub.post("pagamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "replay")
                .entity("{\"a\":1}");
        filter.filter(replay.proxy());

        assertEquals(202, replay.aborted.getStatus());
        assertEquals("{\"status\":\"ok\"}", replay.aborted.getEntity());
        assertEquals(saved.correlationId(), replay.aborted.getHeaderString(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void deveRecusarPayloadDivergenteParaMesmaChave() throws Exception {
        var original = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "duplicada")
                .entity("{\"valor\":10}");
        filter.filter(original.proxy());
        store.existing = original.idempotencyRecord();

        var divergente = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "duplicada")
                .entity("{\"valor\":20}");
        filter.filter(divergente.proxy());

        assertEquals(Response.Status.CONFLICT.getStatusCode(), divergente.aborted.getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", assertInstanceOf(ErrorResponse.class, divergente.aborted.getEntity()).code());
    }

    @Test
    void deveSinalizarRequisicaoEmProcessamento() throws Exception {
        var request = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "processando")
                .entity("{\"valor\":10}");
        filter.filter(request.proxy());
        store.existing = idempotencyRecord(request.idempotencyRecord(), ProcessingStatus.PROCESSING, null, null);

        var retry = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "processando")
                .entity("{\"valor\":10}");
        filter.filter(retry.proxy());

        assertEquals(Response.Status.CONFLICT.getStatusCode(), retry.aborted.getStatus());
        assertEquals("1", retry.aborted.getHeaderString("Retry-After"));
        assertEquals("IDEMPOTENCY_IN_PROGRESS", assertInstanceOf(ErrorResponse.class, retry.aborted.getEntity()).code());
    }

    @Test
    void devePermitirNovaTentativaQuandoFalhaERetryable() throws Exception {
        var request = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "retryable")
                .entity("{\"valor\":10}");
        filter.filter(request.proxy());
        store.existing = idempotencyRecord(request.idempotencyRecord(), ProcessingStatus.FAILED_RETRYABLE, 503, "falhou");

        var retry = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "retryable")
                .entity("{\"valor\":10}");
        filter.filter(retry.proxy());

        assertNull(retry.aborted);
        assertEquals(ProcessingStatus.FAILED_RETRYABLE, retry.idempotencyRecord().processingStatus());
    }

    @Test
    void deveClassificarStatusDeRespostaAoCompletarRegistro() throws Exception {
        var retryable = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "status-503");
        filter.filter(retryable.proxy());
        filter.filter(retryable.proxy(), ResponseContextStub.of(503, "erro"));
        assertEquals(ProcessingStatus.FAILED_RETRYABLE, store.completion.processingStatus);
        assertEquals("erro", store.completion.responseBody);

        var finalFailure = RequestContextStub.post("orcamentos")
                .header(IdempotencyKeyFilter.HEADER_NAME, "status-404");
        filter.filter(finalFailure.proxy());
        filter.filter(finalFailure.proxy(), ResponseContextStub.of(404, null));
        assertEquals(ProcessingStatus.FAILED_FINAL, store.completion.processingStatus);
        assertNull(store.completion.responseBody);
    }

    @Test
    void deveAceitarHeaderLegadoPayloadTextoEValoresFallback() throws Exception {
        var request = RequestContextStub.post("")
                .header("Idempotency-Key", " legado ")
                .header(IdempotencyKeyFilter.HEADER_NAME, " ")
                .entity("texto sem json");

        filter.filter(request.proxy());

        var idempotencyRecord = request.idempotencyRecord();
        assertNull(request.aborted);
        assertEquals("legado", idempotencyRecord.key());
        assertTrue(idempotencyRecord.scope().endsWith("/:anonymous"));
        assertNotNull(idempotencyRecord.correlationId());
        assertNotNull(idempotencyRecord.requestId());
    }

    private static IdempotencyRecord idempotencyRecord(
            IdempotencyRecord source,
            ProcessingStatus status,
            Integer responseStatus,
            String responseBody) {
        return new IdempotencyRecord(
                source.scope(),
                source.key(),
                source.requestHash(),
                status,
                responseStatus,
                responseBody,
                source.correlationId(),
                source.requestId(),
                source.createdAt(),
                OffsetDateTime.now(ZoneOffset.UTC),
                source.expiresAt());
    }

    private static final class FakeStore implements IdempotencyStore {
        private IdempotencyRecord existing;
        private boolean created;
        private Completion completion;

        @Override
        public Optional<IdempotencyRecord> find(String scope, String key) {
            if (existing == null || !existing.scope().equals(scope) || !existing.key().equals(key)) {
                return Optional.empty();
            }
            return Optional.of(existing);
        }

        @Override
        public IdempotencyRecord createProcessing(
                String scope,
                String key,
                String requestHash,
                String correlationId,
                String requestId,
                OffsetDateTime expiresAt) {
            created = true;
            var now = OffsetDateTime.now(ZoneOffset.UTC);
            return new IdempotencyRecord(
                    scope,
                    key,
                    requestHash,
                    ProcessingStatus.PROCESSING,
                    null,
                    null,
                    correlationId,
                    requestId,
                    now,
                    now,
                    expiresAt);
        }

        @Override
        public void complete(
                String scope,
                String key,
                ProcessingStatus processingStatus,
                int responseStatus,
                String responseBody) {
            completion = new Completion(scope, key, processingStatus, responseStatus, responseBody);
        }
    }

    private record Completion(
            String scope,
            String key,
            ProcessingStatus processingStatus,
            int responseStatus,
            String responseBody) {
    }

    private static final class RequestContextStub {
        private final String method;
        private final String path;
        private final Map<String, String> headers = new HashMap<>();
        private final Map<String, Object> properties = new HashMap<>();
        private final MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        private InputStream entityStream = new ByteArrayInputStream(new byte[0]);
        private boolean hasEntity;
        private String principalName;
        private Response aborted;

        private RequestContextStub(String method, String path) {
            this.method = method;
            this.path = path;
        }

        static RequestContextStub get(String path) {
            return new RequestContextStub(HttpMethod.GET, path);
        }

        static RequestContextStub post(String path) {
            return new RequestContextStub(HttpMethod.POST, path);
        }

        RequestContextStub header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        RequestContextStub property(String name, Object value) {
            properties.put(name, value);
            return this;
        }

        RequestContextStub principal(String name) {
            principalName = name;
            return this;
        }

        RequestContextStub query(String name, String value) {
            query.add(name, value);
            return this;
        }

        RequestContextStub entity(String value) {
            hasEntity = true;
            entityStream = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        ContainerRequestContext proxy() {
            InvocationHandler handler = (_, methodInvocation, args) -> switch (methodInvocation.getName()) {
                case "getMethod" -> method;
                case "getHeaderString" -> headers.get((String) args[0]);
                case "abortWith" -> {
                    aborted = (Response) args[0];
                    yield null;
                }
                case "hasEntity" -> hasEntity;
                case "getEntityStream" -> entityStream;
                case "setEntityStream" -> {
                    entityStream = (InputStream) args[0];
                    yield null;
                }
                case "getUriInfo" -> uriInfo();
                case "getSecurityContext" -> securityContext();
                case "getProperty" -> properties.get((String) args[0]);
                case "setProperty" -> {
                    properties.put((String) args[0], args[1]);
                    yield null;
                }
                case "getPropertyNames" -> properties.keySet();
                default -> defaultValue(methodInvocation.getReturnType());
            };
            return IdempotencyKeyFilterTest.proxy(ContainerRequestContext.class, handler);
        }

        IdempotencyRecord idempotencyRecord() {
            return properties.values().stream()
                    .filter(IdempotencyRecord.class::isInstance)
                    .map(IdempotencyRecord.class::cast)
                    .findFirst()
                    .orElseThrow();
        }

        private UriInfo uriInfo() {
            InvocationHandler handler = (_, methodInvocation, _) -> switch (methodInvocation.getName()) {
                case "getPath" -> path;
                case "getQueryParameters" -> query;
                default -> defaultValue(methodInvocation.getReturnType());
            };
            return IdempotencyKeyFilterTest.proxy(UriInfo.class, handler);
        }

        private SecurityContext securityContext() {
            if (principalName == null) {
                return null;
            }
            Principal principal = () -> principalName;
            InvocationHandler handler = (_, methodInvocation, _) -> switch (methodInvocation.getName()) {
                case "getUserPrincipal" -> principal;
                case "isUserInRole", "isSecure" -> false;
                default -> defaultValue(methodInvocation.getReturnType());
            };
            return IdempotencyKeyFilterTest.proxy(SecurityContext.class, handler);
        }
    }

    private static final class ResponseContextStub {
        static ContainerResponseContext of(int status, Object entity) {
            InvocationHandler handler = (_, methodInvocation, _) -> switch (methodInvocation.getName()) {
                case "getStatus" -> status;
                case "getEntity" -> entity;
                default -> defaultValue(methodInvocation.getReturnType());
            };
            return proxy(ContainerResponseContext.class, handler);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(type)) {
            return false;
        }
        if (char.class.equals(type)) {
            return '\0';
        }
        return 0;
    }
}
