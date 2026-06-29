package br.com.oficina.billing.framework.web;

import java.time.OffsetDateTime;
import java.util.List;

public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        String requestId,
        String traceId,
        String spanId,
        String service,
        String logReference,
        List<FieldError> details) {

    public record FieldError(String field, String message, String code) {
    }
}
