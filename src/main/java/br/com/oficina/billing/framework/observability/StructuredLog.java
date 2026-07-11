package br.com.oficina.billing.framework.observability;

import io.opentelemetry.api.trace.Span;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

public final class StructuredLog {
    private StructuredLog() {
    }

    public static void info(Logger logger, String message, Map<String, ?> fields) {
        withFields(fields, () -> logger.info(message));
    }

    public static void withFields(Map<String, ?> fields, Runnable action) {
        var previous = new LinkedHashMap<String, Object>();
        var effectiveFields = withTraceFields(fields);
        for (var entry : effectiveFields.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            previous.put(key, MDC.get(key));
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value.toString());
            }
        }
        try {
            action.run();
        } finally {
            for (var entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    MDC.remove(entry.getKey());
                } else {
                    MDC.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static Map<String, ?> withTraceFields(Map<String, ?> fields) {
        var effectiveFields = new LinkedHashMap<String, Object>();
        if (fields != null) {
            effectiveFields.putAll(fields);
        }
        var spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            effectiveFields.putIfAbsent("traceId", spanContext.getTraceId());
            effectiveFields.putIfAbsent("spanId", spanContext.getSpanId());
        }
        return effectiveFields;
    }
}
