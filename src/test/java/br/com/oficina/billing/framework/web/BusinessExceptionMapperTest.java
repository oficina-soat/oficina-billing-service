package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import br.com.oficina.billing.core.exceptions.BusinessException;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessExceptionMapperTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void deveMapearCodigosDeNegocioParaStatusHttp() {
        MDC.put(CorrelationIdFilter.PROPERTY_NAME, "corr-business-001");
        var mapper = mapper(uriInfo("api/v1/pagamentos"));
        var expectedStatuses = Map.of(
                "VALIDATION_ERROR", 400,
                "BUSINESS_RULE_VIOLATION", 422,
                "DEPENDENCY_UNAVAILABLE", 503,
                "DEPENDENCY_FAILURE", 502,
                "INVALID_STATE_TRANSITION", 409);

        expectedStatuses.forEach((code, status) -> {
            var response = mapper.toResponse(new BusinessException(code, "Falha controlada"));
            var body = assertInstanceOf(ErrorResponse.class, response.getEntity());

            assertEquals(status, response.getStatus());
            assertEquals(code, body.code());
            assertEquals("/api/v1/pagamentos", body.path());
            assertEquals("corr-business-001", response.getHeaderString(CorrelationIdFilter.HEADER_NAME));
        });
    }

    private static BusinessExceptionMapper mapper(UriInfo uriInfo) {
        var factory = new ErrorResponseFactory();
        factory.serviceName = "oficina-billing-service";
        var mapper = new BusinessExceptionMapper(factory);
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
