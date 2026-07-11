package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RecusarPagamentoUseCase {
    private final PagamentoRepositoryGateway repository;
    private final OutboxEventSender outboxEventSender;
    private final Clock clock;

    public RecusarPagamentoUseCase(PagamentoRepositoryGateway repository, OutboxEventSender outboxEventSender) {
        this(repository, outboxEventSender, Clock.systemUTC());
    }

    RecusarPagamentoUseCase(PagamentoRepositoryGateway repository, OutboxEventSender outboxEventSender, Clock clock) {
        this.repository = repository;
        this.outboxEventSender = outboxEventSender;
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
                                "Somente pagamentos criados podem ser recusados.");
                    }
                    return PagamentoStatusUpdater.atualizarStatus(
                            repository,
                            clock,
                            pagamento,
                            StatusPagamento.RECUSADO,
                            command.provedor(),
                            pagamento.transacaoExternaId());
                })
                .thenCompose(atualizado -> PagamentoEventPayloads.registrarEvento(
                        outboxEventSender,
                        atualizado,
                        "pagamentoRecusado",
                        "oficina.billing.pagamento-recusado",
                        "recusadoEm",
                        atualizado.atualizadoEm(),
                        command.provedor(),
                        command.motivo())
                        .thenApply(ignored -> atualizado));
    }

    public record Command(UUID pagamentoId, String provedor, String motivo) {
    }
}
