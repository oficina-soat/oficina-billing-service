package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OutboxPublisherTest {

    @Test
    void deveCalcularMultiplicadorDeRetryComLimiteInferior() {
        assertEquals(1L, OutboxPublisher.retryMultiplier(0));
        assertEquals(1L, OutboxPublisher.retryMultiplier(1));
    }

    @Test
    void deveCalcularMultiplicadorDeRetryComLimiteSuperior() {
        assertEquals(2L, OutboxPublisher.retryMultiplier(2));
        assertEquals(1024L, OutboxPublisher.retryMultiplier(20));
    }
}
