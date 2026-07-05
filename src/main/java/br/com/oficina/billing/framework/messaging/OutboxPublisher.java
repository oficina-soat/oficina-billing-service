package br.com.oficina.billing.framework.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class OutboxPublisher {
    private final BillingEventStore store;

    public OutboxPublisher(BillingEventStore store) {
        this.store = store;
    }

    public List<OutboxEventRecord> publicarPendentes() {
        return store.publicarPendentes();
    }
}
