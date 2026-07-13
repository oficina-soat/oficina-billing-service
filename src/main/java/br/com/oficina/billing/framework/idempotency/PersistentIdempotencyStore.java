package br.com.oficina.billing.framework.idempotency;

import br.com.oficina.billing.framework.idempotency.IdempotencyRecord.ProcessingStatus;
import br.com.oficina.billing.framework.observability.OperationalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PersistentIdempotencyStore implements IdempotencyStore {
    private final IdempotencyStore delegate;
    private final OperationalMetrics metrics;
    private final String database;

    @Inject
    public PersistentIdempotencyStore(
            @ConfigProperty(name = "oficina.persistence.kind") String persistenceKind,
            Instance<DataSource> dataSources,
            OperationalMetrics metrics) {
        this.delegate = createDelegate(persistenceKind, dataSources);
        this.metrics = metrics;
        this.database = persistenceKind.toLowerCase(java.util.Locale.ROOT);
    }

    public PersistentIdempotencyStore(DataSource dataSource) {
        this.delegate = new PostgresIdempotencyStore(dataSource);
        this.metrics = new OperationalMetrics(new SimpleMeterRegistry(), "oficina-billing-service");
        this.database = "postgresql";
    }

    private IdempotencyStore createDelegate(String persistenceKind, Instance<DataSource> dataSources) {
        return switch (persistenceKind.toLowerCase(java.util.Locale.ROOT)) {
            case "memory" -> new InMemoryIdempotencyStore();
            case "postgresql" -> new PostgresIdempotencyStore(dataSources.get());
            default -> throw new IllegalStateException("Tipo de persistencia nao suportado: " + persistenceKind);
        };
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, String key) {
        return metrics.persistence(database, "idempotency", "find", () -> delegate.find(scope, key));
    }

    @Override
    public IdempotencyRecord createProcessing(
            String scope,
            String key,
            String requestHash,
            String correlationId,
            String requestId,
            OffsetDateTime expiresAt) {
        return metrics.persistence(
                database,
                "idempotency",
                "create_processing",
                () -> delegate.createProcessing(scope, key, requestHash, correlationId, requestId, expiresAt));
    }

    @Override
    public void complete(
            String scope,
            String key,
            ProcessingStatus processingStatus,
            int responseStatus,
            String responseBody) {
        metrics.persistence(
                database,
                "idempotency",
                "complete",
                () -> delegate.complete(scope, key, processingStatus, responseStatus, responseBody));
    }
}
