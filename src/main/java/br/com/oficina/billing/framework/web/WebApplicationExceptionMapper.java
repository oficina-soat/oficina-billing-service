package br.com.oficina.billing.framework.web;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;

@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Context
    UriInfo uriInfo;

    @ConfigProperty(name = "quarkus.application.name")
    String serviceName;

    @Override
    public Response toResponse(WebApplicationException exception) {
        int status = exception.getResponse().getStatus();
        String correlationId = correlationId();
        var responseStatus = Response.Status.fromStatusCode(status);
        var error = responseStatus == null ? "HTTP " + status : responseStatus.getReasonPhrase();
        var timestamp = OffsetDateTime.now(ZoneOffset.UTC);

        ErrorResponse body = new ErrorResponse(
                timestamp,
                status,
                error,
                code(status, exception),
                exception.getMessage(),
                path(),
                correlationId,
                null,
                null,
                null,
                serviceName,
                logReference(timestamp, correlationId),
                List.of());

        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIdFilter.HEADER_NAME, correlationId)
                .entity(body)
                .build();
    }

    private String correlationId() {
        Object correlationId = MDC.get(CorrelationIdFilter.PROPERTY_NAME);
        return correlationId == null ? UUID.randomUUID().toString() : correlationId.toString();
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

    private String path() {
        if (uriInfo == null) {
            return null;
        }
        return "/" + uriInfo.getPath().replaceFirst("^/+", "");
    }

    private String logReference(OffsetDateTime timestamp, String correlationId) {
        if (correlationId == null) {
            return null;
        }
        return serviceName + "/" + timestamp.toLocalDate() + "/" + correlationId;
    }
}
