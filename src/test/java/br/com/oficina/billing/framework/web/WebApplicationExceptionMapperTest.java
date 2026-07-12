package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Proxy;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebApplicationExceptionMapperTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void deveMapearNotFound() {
        MDC.put(CorrelationIdFilter.PROPERTY_NAME, "corr-billing-001");
        var mapper = mapper(uriInfo("api/v1/pagamentos"));

        var response = mapper.toResponse(new NotFoundException("Pagamento nao encontrado"));
        var body = assertInstanceOf(ErrorResponse.class, response.getEntity());

        assertEquals(404, response.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", body.code());
        assertEquals("/api/v1/pagamentos", body.path());
        assertEquals("corr-billing-001", response.getHeaderString(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void deveMapearIdempotencyKeyObrigatoria() {
        var mapper = mapper(uriInfo("api/v1/orcamentos"));

        var response = mapper.toResponse(new BadRequestException("Header Idempotency-Key e obrigatorio."));
        var body = assertInstanceOf(ErrorResponse.class, response.getEntity());

        assertEquals(400, response.getStatus());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", body.code());
    }

    @Test
    void deveMapearStatusHttpGenerico() {
        var mapper = mapper(null);
        var exception = new WebApplicationException("Conflito externo", Response.status(423).build());

        var response = mapper.toResponse(exception);
        var body = assertInstanceOf(ErrorResponse.class, response.getEntity());

        assertEquals(423, response.getStatus());
        assertEquals("HTTP_423", body.code());
    }

    private static WebApplicationExceptionMapper mapper(UriInfo uriInfo) {
        var factory = new ErrorResponseFactory();
        factory.serviceName = "oficina-billing-service";
        var mapper = new WebApplicationExceptionMapper(factory);
        mapper.uriInfo = uriInfo;
        return mapper;
    }

    private static UriInfo uriInfo(String path) {
        return (UriInfo) Proxy.newProxyInstance(
                UriInfo.class.getClassLoader(),
                new Class<?>[] {UriInfo.class},
                (_, method, _) -> {
                    if ("getPath".equals(method.getName()) && method.getParameterCount() == 0) {
                        return path;
                    }
                    return null;
                });
    }
}
