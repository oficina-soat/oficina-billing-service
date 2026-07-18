package br.com.oficina.billing.framework.db;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
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
    private final ConcurrentHashMap<UUID, ProviderClaim> providerClaims = new ConcurrentHashMap<>();

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
    public synchronized CompletableFuture<Boolean> claimProviderRequest(
            UUID orcamentoId,
            UUID ownerId,
            OffsetDateTime claimedAt,
            OffsetDateTime claimUntil) {
        var current = providerClaims.get(orcamentoId);
        if (current != null && current.claimUntil().isAfter(claimedAt)) {
            return CompletableFuture.completedFuture(false);
        }
        providerClaims.put(orcamentoId, new ProviderClaim(ownerId, claimUntil));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletableFuture<Void> releaseProviderRequest(UUID orcamentoId, UUID ownerId) {
        providerClaims.computeIfPresent(
                orcamentoId,
                (ignored, claim) -> claim.ownerId().equals(ownerId) ? null : claim);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findById(UUID pagamentoId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(storage.get(pagamentoId)));
    }

    @Override
    public CompletableFuture<Optional<Pagamento>> findByTransacaoExternaId(String transacaoExternaId) {
        return CompletableFuture.completedFuture(storage.values().stream()
                .filter(pagamento -> transacaoExternaId.equals(pagamento.transacaoExternaId()))
                .findFirst());
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

    @Override
    public synchronized CompletableFuture<UpdateResult> updateIfStatus(
            Pagamento pagamento,
            br.com.oficina.billing.core.entities.StatusPagamento expectedStatus) {
        var atual = storage.get(pagamento.pagamentoId());
        if (atual == null || atual.status() != expectedStatus) {
            return CompletableFuture.completedFuture(new UpdateResult(atual, false));
        }
        storage.put(pagamento.pagamentoId(), pagamento);
        return CompletableFuture.completedFuture(new UpdateResult(pagamento, true));
    }

    private record ProviderClaim(UUID ownerId, OffsetDateTime claimUntil) {
    }
}
