package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CancelarPagamentosCriadosDaOrdemUseCase {
    private final PagamentoRepositoryGateway repository;
    private final Clock clock;

    public CancelarPagamentosCriadosDaOrdemUseCase(PagamentoRepositoryGateway repository) {
        this(repository, Clock.systemUTC());
    }

    CancelarPagamentosCriadosDaOrdemUseCase(PagamentoRepositoryGateway repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public CompletableFuture<List<Pagamento>> executar(Command command) {
        return repository.findByOrdemServicoId(command.ordemServicoId())
                .thenCompose(pagamentos -> {
                    var atualizacoes = pagamentos.stream()
                            .filter(pagamento -> pagamento.status() == StatusPagamento.CRIADO)
                            .map(pagamento -> PagamentoStatusUpdater.atualizarStatus(
                                    repository,
                                    clock,
                                    pagamento,
                                    StatusPagamento.CANCELADO,
                                    pagamento.provedor(),
                                    pagamento.transacaoExternaId()))
                            .toList();
                    return CompletableFuture.allOf(atualizacoes.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> atualizacoes.stream()
                                    .map(CompletableFuture::join)
                                    .toList());
                });
    }

    public record Command(UUID ordemServicoId) {
    }
}
