package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DomainMessagingWorkerTest {

    @Test
    void naoDeveIniciarExecutorQuandoWorkerEstiverDesabilitado() {
        var worker = new DomainMessagingWorker(
                new CountingOutboxPublisher(false),
                new CountingSqsDomainEventConsumer(),
                false,
                1);

        worker.start();

        assertDoesNotThrow(worker::stop);
    }

    @Test
    void deveExecutarCicloDeMensageria() {
        var publisher = new CountingOutboxPublisher(false);
        var consumer = new CountingSqsDomainEventConsumer();
        var worker = new DomainMessagingWorker(publisher, consumer, false, 1);

        assertDoesNotThrow(() -> invokeTick(worker));

        assertEquals(1, publisher.publishCalls);
        assertEquals(1, consumer.consumeCalls);
    }

    @Test
    void deveIsolarFalhaDoCicloDeMensageria() {
        var publisher = new CountingOutboxPublisher(true);
        var consumer = new CountingSqsDomainEventConsumer();
        var worker = new DomainMessagingWorker(publisher, consumer, false, 1);

        assertDoesNotThrow(() -> invokeTick(worker));

        assertEquals(1, publisher.publishCalls);
        assertEquals(0, consumer.consumeCalls);
    }

    @Test
    void deveEncerrarExecutorQuandoWorkerEstiverHabilitado() {
        var worker = new DomainMessagingWorker(
                new CountingOutboxPublisher(false),
                new CountingSqsDomainEventConsumer(),
                true,
                1);

        worker.start();

        assertDoesNotThrow(worker::stop);
    }

    private static void invokeTick(DomainMessagingWorker worker) throws ReflectiveOperationException {
        var tick = DomainMessagingWorker.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(worker);
    }

    private static final class CountingOutboxPublisher extends OutboxPublisher {
        private final boolean fail;
        private int publishCalls;

        private CountingOutboxPublisher(boolean fail) {
            super(new InMemoryBillingEventStore());
            this.fail = fail;
        }

        @Override
        public List<OutboxEventRecord> publicarPendentes() {
            publishCalls++;
            if (fail) {
                throw new IllegalStateException("publisher failed");
            }
            return List.of();
        }
    }

    private static final class CountingSqsDomainEventConsumer extends SqsDomainEventConsumer {
        private int consumeCalls;

        private CountingSqsDomainEventConsumer() {
            super(null, null, null, false, 0, 0);
        }

        @Override
        int consumirDisponiveis() {
            consumeCalls++;
            return 1;
        }
    }
}
