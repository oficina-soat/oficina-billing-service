package br.com.oficina.billing.core.interfaces.gateway;

import br.com.oficina.billing.core.entities.Orcamento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface OrcamentoRepositoryGateway {
    CompletableFuture<Orcamento> save(Orcamento orcamento);

    CompletableFuture<Optional<Orcamento>> findById(UUID orcamentoId);

    CompletableFuture<List<Orcamento>> findByOrdemServicoId(UUID ordemServicoId);
}
