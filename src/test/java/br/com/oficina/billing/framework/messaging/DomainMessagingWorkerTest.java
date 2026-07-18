package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.Startup;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DomainMessagingWorkerTest {

    @Test
    void deveSerInicializadoNoStartupDaAplicacao() {
        assertTrue(DomainMessagingWorker.class.isAnnotationPresent(Startup.class));
    }

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
    void deveExecutarCiclosIndependentesDeMensageria() {
        var publisher = new CountingOutboxPublisher(false);
        var consumer = new CountingSqsDomainEventConsumer();
        var worker = new DomainMessagingWorker(publisher, consumer, false, 1);

        assertDoesNotThrow(() -> invokeTick(worker, "publishTick"));
        assertDoesNotThrow(() -> invokeConsumeTick(worker, DomainMessagingRoutes.consumedTopics().getFirst()));

        assertEquals(1, publisher.publishCalls);
        assertEquals(1, consumer.consumeCalls);
    }

    @Test
    void deveIsolarFalhaDoPublisherDoCicloDeConsumo() {
        var publisher = new CountingOutboxPublisher(true);
        var consumer = new CountingSqsDomainEventConsumer();
        var worker = new DomainMessagingWorker(publisher, consumer, false, 1);

        assertDoesNotThrow(() -> invokeTick(worker, "publishTick"));
        assertDoesNotThrow(() -> invokeConsumeTick(worker, DomainMessagingRoutes.consumedTopics().getFirst()));

        assertEquals(1, publisher.publishCalls);
        assertEquals(1, consumer.consumeCalls);
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

    @Test
    void filaBloqueadaNaoDeveImpedirOutraFila() throws InterruptedException {
        var topics = DomainMessagingRoutes.consumedTopics();
        var consumer = new BlockingSqsDomainEventConsumer(topics.getFirst(), topics.get(1));
        var worker = new DomainMessagingWorker(
                new CountingOutboxPublisher(false), consumer, true, 50, 100);

        worker.start();

        assertTrue(consumer.otherQueuePolled.await(2, TimeUnit.SECONDS));
        worker.stop();
    }

    private static void invokeTick(DomainMessagingWorker worker, String methodName) throws ReflectiveOperationException {
        var tick = DomainMessagingWorker.class.getDeclaredMethod(methodName);
        tick.setAccessible(true);
        tick.invoke(worker);
    }

    private static void invokeConsumeTick(DomainMessagingWorker worker, String topic) throws ReflectiveOperationException {
        var tick = DomainMessagingWorker.class.getDeclaredMethod("consumeTick", String.class);
        tick.setAccessible(true);
        tick.invoke(worker, topic);
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

        @Override
        int consumirDisponiveis(String topic) {
            consumeCalls++;
            return 1;
        }
    }

    private static final class BlockingSqsDomainEventConsumer extends SqsDomainEventConsumer {
        private final String blockedTopic;
        private final String observedTopic;
        private final CountDownLatch otherQueuePolled = new CountDownLatch(1);

        private BlockingSqsDomainEventConsumer(String blockedTopic, String observedTopic) {
            super(null, null, null, false, 0, 0);
            this.blockedTopic = blockedTopic;
            this.observedTopic = observedTopic;
        }

        @Override
        int consumirDisponiveis(String topic) {
            if (blockedTopic.equals(topic)) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            } else if (observedTopic.equals(topic)) {
                otherQueuePolled.countDown();
            }
            return 0;
        }
    }
}
