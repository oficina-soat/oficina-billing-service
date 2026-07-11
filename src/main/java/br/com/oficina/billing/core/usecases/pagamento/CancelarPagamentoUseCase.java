package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CancelarPagamentoUseCase {
    private final PagamentoRepositoryGateway repository;
    private final Clock clock;

    public CancelarPagamentoUseCase(PagamentoRepositoryGateway repository) {
        this(repository, Clock.systemUTC());
    }

    CancelarPagamentoUseCase(PagamentoRepositoryGateway repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public CompletableFuture<Pagamento> executar(Command command) {
        return repository.findById(command.pagamentoId())
                .thenCompose(optional -> {
                    var pagamento = optional.orElseThrow(() ->
                            new ResourceNotFoundException("Pagamento nao encontrado."));
                    if (pagamento.status() != StatusPagamento.CRIADO) {
                        throw new BusinessException(
                                "INVALID_STATE_TRANSITION",
                                "Somente pagamentos criados podem ser cancelados.");
                    }
                    return PagamentoStatusUpdater.atualizarStatus(
                            repository,
                            clock,
                            pagamento,
                            StatusPagamento.CANCELADO,
                            pagamento.provedor(),
                            pagamento.transacaoExternaId());
                });
    }

    public record Command(UUID pagamentoId) {
    }
}
