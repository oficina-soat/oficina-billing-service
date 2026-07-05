package br.com.oficina.billing.framework.web;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.MDC;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {
    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String PROPERTY_NAME = "correlationId";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String correlationId = Optional.ofNullable(requestContext.getHeaderString(HEADER_NAME))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());

        requestContext.setProperty(PROPERTY_NAME, correlationId);
        MDC.put(PROPERTY_NAME, correlationId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        Object correlationId = requestContext.getProperty(PROPERTY_NAME);
        if (correlationId != null) {
            responseContext.getHeaders().putSingle(HEADER_NAME, correlationId.toString());
        }
        MDC.remove(PROPERTY_NAME);
    }
}
