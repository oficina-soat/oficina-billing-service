package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PagamentoRepositoryGateway {
    CompletableFuture<Pagamento> save(Pagamento pagamento);

    CompletableFuture<CreateResult> createIfAbsent(Pagamento pagamento);

    CompletableFuture<Boolean> claimProviderRequest(
            UUID orcamentoId,
            UUID ownerId,
            OffsetDateTime claimedAt,
            OffsetDateTime claimUntil);

    CompletableFuture<Void> releaseProviderRequest(UUID orcamentoId, UUID ownerId);

    CompletableFuture<Optional<Pagamento>> findById(UUID pagamentoId);

    CompletableFuture<Optional<Pagamento>> findByTransacaoExternaId(String transacaoExternaId);

    default CompletableFuture<Optional<Pagamento>> findByTransacaoExternaId(
            String transacaoExternaId,
            TipoReferenciaExternaPagamento tipoReferenciaExterna) {
        return findByTransacaoExternaId(transacaoExternaId)
                .thenApply(optional -> optional.filter(
                        pagamento -> pagamento.tipoReferenciaExterna() == tipoReferenciaExterna));
    }

    CompletableFuture<List<Pagamento>> findByOrdemServicoId(UUID ordemServicoId);

    CompletableFuture<Optional<Pagamento>> findByOrcamentoId(UUID orcamentoId);

    CompletableFuture<List<Pagamento>> findAll();

    CompletableFuture<UpdateResult> updateIfStatus(Pagamento pagamento, br.com.oficina.billing.core.entities.StatusPagamento expectedStatus);

    record CreateResult(Pagamento pagamento, boolean created) {
    }

    record UpdateResult(Pagamento pagamento, boolean updated) {
    }
}
