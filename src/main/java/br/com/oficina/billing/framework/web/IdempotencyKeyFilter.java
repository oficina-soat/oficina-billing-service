package br.com.oficina.billing.framework.web;

import jakarta.annotation.Priority;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Set;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class IdempotencyKeyFilter implements ContainerRequestFilter {
    public static final String HEADER_NAME = "Idempotency-Key";
    private static final Set<String> MUTATING_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, "PATCH");

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!MUTATING_METHODS.contains(requestContext.getMethod())) {
            return;
        }

        String key = requestContext.getHeaderString(HEADER_NAME);
        if (key == null || key.isBlank()) {
            throw new WebApplicationException("Header Idempotency-Key obrigatorio para operacoes mutaveis.", Response.Status.BAD_REQUEST);
        }
    }
}
