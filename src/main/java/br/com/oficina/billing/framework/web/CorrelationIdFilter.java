package br.com.oficina.billing.framework.web;

import br.com.oficina.billing.framework.observability.StructuredLog;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final Logger LOG = Logger.getLogger(CorrelationIdFilter.class);
    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String PROPERTY_NAME = "correlationId";
    private static final String REQUEST_ID_PROPERTY = "requestId";
    private static final String START_TIME_NANOS_PROPERTY = "requestStartTimeNanos";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String correlationId = Optional.ofNullable(requestContext.getHeaderString(HEADER_NAME))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
        String requestId = UUID.randomUUID().toString();

        requestContext.setProperty(PROPERTY_NAME, correlationId);
        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);
        requestContext.setProperty(START_TIME_NANOS_PROPERTY, System.nanoTime());
        MDC.put(PROPERTY_NAME, correlationId);
        MDC.put(REQUEST_ID_PROPERTY, requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        Object correlationId = requestContext.getProperty(PROPERTY_NAME);
        if (correlationId != null) {
            responseContext.getHeaders().putSingle(HEADER_NAME, correlationId.toString());
        }
        logRequest(requestContext, responseContext, correlationId);
        MDC.remove(PROPERTY_NAME);
        MDC.remove(REQUEST_ID_PROPERTY);
    }

    private void logRequest(
            ContainerRequestContext requestContext,
            ContainerResponseContext responseContext,
            Object correlationId) {
        var path = path(requestContext);
        if (path.startsWith("/q/")) {
            return;
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put(PROPERTY_NAME, correlationId);
        fields.put(REQUEST_ID_PROPERTY, requestContext.getProperty(REQUEST_ID_PROPERTY));
        fields.put("http.method", requestContext.getMethod());
        fields.put("http.path", path);
        fields.put("http.status", responseContext.getStatus());
        fields.put("durationMs", durationMs(requestContext));
        StructuredLog.info(LOG, "http request processed", fields);
    }

    private String path(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();
        return path == null || path.isBlank() ? "/" : "/" + path;
    }

    private long durationMs(ContainerRequestContext requestContext) {
        var startTime = requestContext.getProperty(START_TIME_NANOS_PROPERTY);
        if (startTime instanceof Long startTimeNanos) {
            return (System.nanoTime() - startTimeNanos) / 1_000_000;
        }
        return 0L;
    }
}
