package br.com.oficina.billing.framework.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StructuredLogTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldExposeQueryableEventTypeAliases() {
        StructuredLog.withFields(Map.of("eventType", "orcamentoGerado"), () -> {
            assertEquals("orcamentoGerado", MDC.get("eventType"));
            assertEquals("orcamentoGerado", MDC.get("domainEventType"));
            assertEquals("orcamentoGerado", MDC.get("event.type"));
        });
    }
}
