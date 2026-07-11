package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

final class PagamentoStatusUpdater {
    private PagamentoStatusUpdater() {
    }

    static CompletableFuture<Pagamento> atualizarStatus(
            PagamentoRepositoryGateway repository,
            Clock clock,
            Pagamento pagamento,
            StatusPagamento status,
            String provedor,
            String transacaoExternaId) {
        var atualizado = new Pagamento(
                pagamento.pagamentoId(),
                pagamento.ordemServicoId(),
                pagamento.orcamentoId(),
                pagamento.valor(),
                pagamento.metodo(),
                status,
                provedor,
                transacaoExternaId,
                pagamento.criadoEm(),
                OffsetDateTime.now(clock));
        return repository.save(atualizado);
    }
}
