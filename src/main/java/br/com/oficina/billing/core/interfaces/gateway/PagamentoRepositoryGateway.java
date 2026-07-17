package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.Pagamento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PagamentoRepositoryGateway {
    CompletableFuture<Pagamento> save(Pagamento pagamento);

    CompletableFuture<Optional<Pagamento>> findById(UUID pagamentoId);

    CompletableFuture<List<Pagamento>> findByOrdemServicoId(UUID ordemServicoId);

    CompletableFuture<Optional<Pagamento>> findByOrcamentoId(UUID orcamentoId);

    CompletableFuture<List<Pagamento>> findAll();
}
