package br.com.oficina.billing.framework.web;

import br.com.oficina.billing.core.exceptions.BusinessException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class BusinessExceptionMapper implements ExceptionMapper<BusinessException> {
    @Context
    UriInfo uriInfo;

    private final ErrorResponseFactory errorResponseFactory;

    public BusinessExceptionMapper(ErrorResponseFactory errorResponseFactory) {
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public Response toResponse(BusinessException exception) {
        int status = switch (exception.code()) {
            case "VALIDATION_ERROR" -> Response.Status.BAD_REQUEST.getStatusCode();
            case "BUSINESS_RULE_VIOLATION" -> 422;
            default -> Response.Status.CONFLICT.getStatusCode();
        };
        var body = errorResponseFactory.create(status, exception.code(), exception.getMessage(), uriInfo);
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header(CorrelationIdFilter.HEADER_NAME, body.correlationId())
                .entity(body)
                .build();
    }
}
