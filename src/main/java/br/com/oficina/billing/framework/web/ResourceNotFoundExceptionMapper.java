package br.com.oficina.billing.framework.web;

import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ResourceNotFoundExceptionMapper implements ExceptionMapper<ResourceNotFoundException> {
    @Context
    UriInfo uriInfo;

    private final ErrorResponseFactory errorResponseFactory;

    public ResourceNotFoundExceptionMapper(ErrorResponseFactory errorResponseFactory) {
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public Response toResponse(ResourceNotFoundException exception) {
        var status = Response.Status.NOT_FOUND;
        var body = errorResponseFactory.create(status.getStatusCode(), "RESOURCE_NOT_FOUND", exception.getMessage(), uriInfo);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIdFilter.HEADER_NAME, body.correlationId())
                .entity(body)
                .build();
    }
}
