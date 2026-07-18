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

public class ConfirmarPagamentoUseCase {
    private final PagamentoRepositoryGateway repository;
    private final OutboxEventSender outboxEventSender;
    private final Clock clock;

    public ConfirmarPagamentoUseCase(PagamentoRepositoryGateway repository, OutboxEventSender outboxEventSender) {
        this(repository, outboxEventSender, Clock.systemUTC());
    }

    ConfirmarPagamentoUseCase(PagamentoRepositoryGateway repository, OutboxEventSender outboxEventSender, Clock clock) {
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
                                "Somente pagamentos criados podem ser confirmados.");
                    }
                    validarPagamentoManual(pagamento, command.provedor());
                    return PagamentoStatusUpdater.atualizarStatus(
                            repository,
                            clock,
                            pagamento,
                            StatusPagamento.CONFIRMADO,
                            command.provedor(),
                            command.transacaoExternaId());
                })
                .thenCompose(atualizado -> PagamentoEventPayloads.registrarEvento(new PagamentoEventPayloads.Registro(
                        outboxEventSender,
                        atualizado,
                        PagamentoEventPayloads.PAGAMENTO_CONFIRMADO,
                        atualizado.atualizadoEm(),
                        null,
                        null))
                        .thenApply(ignored -> atualizado));
    }

    private void validarPagamentoManual(Pagamento pagamento, String provedorInformado) {
        if ((pagamento.provedor() != null && !pagamento.provedor().isBlank())
                || "mercado-pago".equalsIgnoreCase(provedorInformado)) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Pagamentos integrados devem ser confirmados por reconciliacao com o provedor.");
        }
    }

    public record Command(UUID pagamentoId, String provedor, String transacaoExternaId) {
    }
}
