package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConsultarPagamentoUseCase {
    private final PagamentoRepositoryGateway repository;

    public ConsultarPagamentoUseCase(PagamentoRepositoryGateway repository) {
        this.repository = repository;
    }

    public CompletableFuture<Pagamento> executar(Command command) {
        return repository.findById(command.pagamentoId())
                .thenApply(optional -> optional.orElseThrow(() ->
                        new ResourceNotFoundException("Pagamento nao encontrado.")));
    }

    public record Command(UUID pagamentoId) {
    }
}
