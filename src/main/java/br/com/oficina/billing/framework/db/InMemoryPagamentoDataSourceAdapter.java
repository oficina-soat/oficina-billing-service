package br.com.oficina.billing.framework.db;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
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
public class InMemoryPagamentoDataSourceAdapter implements PagamentoRepositoryGateway {
    private final ConcurrentHashMap<UUID, Pagamento> storage = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Pagamento> save(Pagamento pagamento) {
        storage.put(pagamento.pagamentoId(), pagamento);
        return CompletableFuture.completedFuture(pagamento);
    }

    @Override
    public synchronized CompletableFuture<CreateResult> createIfAbsent(Pagamento pagamento) {
        var existente = storage.values().stream()
                .filter(item -> item.orcamentoId().equals(pagamento.orcamentoId()))
                .findFirst();
        if (existente.isPresent()) {
            return CompletableFuture.completedFuture(new CreateResult(existente.orElseThrow(), false));
        }
        storage.put(pagamento.pagamentoId(), pagamento);
        return CompletableFuture.completedFuture(new CreateResult(pagamento, true));
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findById(UUID pagamentoId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(storage.get(pagamentoId)));
    }

    @Override
    public CompletableFuture<List<Pagamento>> findByOrdemServicoId(UUID ordemServicoId) {
        var pagamentos = storage.values().stream()
                .filter(pagamento -> pagamento.ordemServicoId().equals(ordemServicoId))
                .sorted(Comparator.comparing(Pagamento::criadoEm))
                .toList();
        return CompletableFuture.completedFuture(pagamentos);
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findByOrcamentoId(UUID orcamentoId) {
        var encontrado = storage.values().stream()
                .filter(pagamento -> pagamento.orcamentoId().equals(orcamentoId))
                .findFirst();
        return CompletableFuture.completedFuture(encontrado);
    }

    @Override
    public CompletableFuture<List<Pagamento>> findAll() {
        return CompletableFuture.completedFuture(storage.values().stream()
                .sorted(Comparator.comparing(Pagamento::atualizadoEm))
                .toList());
    }
}
