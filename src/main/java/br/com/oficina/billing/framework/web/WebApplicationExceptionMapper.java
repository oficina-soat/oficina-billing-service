package br.com.oficina.billing.framework.web;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.time.OffsetDateTime;
import java.util.List;
import org.jboss.logging.MDC;

@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(WebApplicationException exception) {
        int status = exception.getResponse().getStatus();
        Object correlationId = MDC.get(CorrelationIdFilter.PROPERTY_NAME);

        ErrorResponse body = new ErrorResponse(
                "https://oficina.example/errors/http-" + status,
                Response.Status.fromStatusCode(status).getReasonPhrase(),
                status,
                exception.getMessage(),
                uriInfo == null ? null : uriInfo.getPath(),
                "HTTP_" + status,
                correlationId == null ? null : correlationId.toString(),
                OffsetDateTime.now(),
                List.of());

        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
