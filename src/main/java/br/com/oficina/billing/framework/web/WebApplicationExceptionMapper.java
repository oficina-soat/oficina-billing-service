package br.com.oficina.billing.framework.web;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Context
    UriInfo uriInfo;

    private final ErrorResponseFactory errorResponseFactory;

    public WebApplicationExceptionMapper(ErrorResponseFactory errorResponseFactory) {
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public Response toResponse(WebApplicationException exception) {
        int status = exception.getResponse().getStatus();
        ErrorResponse body = errorResponseFactory.create(
                status,
                code(status, exception),
                exception.getMessage(),
                uriInfo);

        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIdFilter.HEADER_NAME, body.correlationId())
                .entity(body)
                .build();
    }

    private String code(int status, WebApplicationException exception) {
        if (status == Response.Status.NOT_FOUND.getStatusCode()) {
            return "RESOURCE_NOT_FOUND";
        }
        if (status == Response.Status.BAD_REQUEST.getStatusCode()
                && exception.getMessage() != null
                && exception.getMessage().contains("Idempotency-Key")) {
            return "IDEMPOTENCY_KEY_REQUIRED";
        }
        return "HTTP_" + status;
    }
}
