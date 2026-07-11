package br.com.oficina.billing.framework.db;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@IfBuildProperty(name = "oficina.persistence.kind", stringValue = "memory")
public class InMemoryOrcamentoDataSourceAdapter implements OrcamentoRepositoryGateway {
    private final ConcurrentHashMap<UUID, Orcamento> storage = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Orcamento> save(Orcamento orcamento) {
        storage.put(orcamento.orcamentoId(), orcamento);
        return CompletableFuture.completedFuture(orcamento);
    }

    @Override
    public CompletableFuture<Optional<Orcamento>> findById(UUID orcamentoId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(storage.get(orcamentoId)));
    }

    @Override
    public CompletableFuture<List<Orcamento>> findByOrdemServicoId(UUID ordemServicoId) {
        var orcamentos = storage.values().stream()
                .filter(orcamento -> orcamento.ordemServicoId().equals(ordemServicoId))
                .sorted(Comparator.comparing(Orcamento::criadoEm))
                .toList();
        return CompletableFuture.completedFuture(orcamentos);
    }
}
