package br.com.oficina.billing.core.usecases.orcamento;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConsultarOrcamentosDaOrdemServicoUseCase {
    private final OrcamentoRepositoryGateway repository;

    public ConsultarOrcamentosDaOrdemServicoUseCase(OrcamentoRepositoryGateway repository) {
        this.repository = repository;
    }

    public CompletableFuture<List<Orcamento>> executar(Command command) {
        return repository.findByOrdemServicoId(command.ordemServicoId());
    }

    public record Command(UUID ordemServicoId) {
    }
}
