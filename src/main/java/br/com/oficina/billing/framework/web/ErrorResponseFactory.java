package br.com.oficina.billing.framework.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;

@ApplicationScoped
public class ErrorResponseFactory {
    @ConfigProperty(name = "quarkus.application.name")
    String serviceName;

    public ErrorResponse create(int status, String code, String message, UriInfo uriInfo) {
        var timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        var responseStatus = Response.Status.fromStatusCode(status);
        var error = responseStatus == null ? "HTTP " + status : responseStatus.getReasonPhrase();
        var correlationId = correlationId();

        return new ErrorResponse(
                timestamp,
                status,
                error,
                code,
                message,
                path(uriInfo),
                correlationId,
                null,
                null,
                null,
                serviceName,
                logReference(timestamp, correlationId),
                List.of());
    }

    private String correlationId() {
        Object correlationId = MDC.get(CorrelationIdFilter.PROPERTY_NAME);
        return correlationId == null ? UUID.randomUUID().toString() : correlationId.toString();
    }

    private String path(UriInfo uriInfo) {
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
