package br.com.oficina.billing.framework.web;

import java.time.OffsetDateTime;
import java.util.List;

public record ErrorResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String errorCode,
        String correlationId,
        OffsetDateTime timestamp,
        List<FieldError> errors) {

    public record FieldError(String field, String message, String code) {
    }
}
