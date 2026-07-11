package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConsultarPagamentosDaOrdemServicoUseCase {
    private final PagamentoRepositoryGateway repository;

    public ConsultarPagamentosDaOrdemServicoUseCase(PagamentoRepositoryGateway repository) {
        this.repository = repository;
    }

    public CompletableFuture<List<Pagamento>> executar(Command command) {
        return repository.findByOrdemServicoId(command.ordemServicoId());
    }

    public record Command(UUID ordemServicoId) {
    }
}
