package br.com.oficina.billing.core.usecases.orcamento;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConsultarOrcamentoUseCase {
    private final OrcamentoRepositoryGateway repository;

    public ConsultarOrcamentoUseCase(OrcamentoRepositoryGateway repository) {
        this.repository = repository;
    }

    public CompletableFuture<Orcamento> executar(Command command) {
        return repository.findById(command.orcamentoId())
                .thenApply(optional -> optional.orElseThrow(() ->
                        new ResourceNotFoundException("Orcamento nao encontrado.")));
    }

    public record Command(UUID orcamentoId) {
    }
}
