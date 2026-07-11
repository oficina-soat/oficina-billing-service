package br.com.oficina.billing.core.usecases.orcamento;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RecusarOrcamentoUseCase {
    private final OrcamentoRepositoryGateway repository;
    private final OutboxEventSender outboxEventSender;
    private final Clock clock;

    public RecusarOrcamentoUseCase(OrcamentoRepositoryGateway repository, OutboxEventSender outboxEventSender) {
        this(repository, outboxEventSender, Clock.systemUTC());
    }

    RecusarOrcamentoUseCase(OrcamentoRepositoryGateway repository, OutboxEventSender outboxEventSender, Clock clock) {
        this.repository = repository;
        this.outboxEventSender = outboxEventSender;
        this.clock = clock;
    }

    public CompletableFuture<Orcamento> executar(Command command) {
        return repository.findById(command.orcamentoId())
                .thenCompose(optional -> {
                    var orcamento = optional.orElseThrow(() ->
                            new ResourceNotFoundException("Orcamento nao encontrado."));
                    if (orcamento.status() != StatusOrcamento.GERADO) {
                        throw new BusinessException(
                                "INVALID_STATE_TRANSITION",
                                "Somente orcamentos gerados podem ser recusados.");
                    }
                    return atualizarStatus(orcamento, StatusOrcamento.RECUSADO)
                            .thenCompose(atualizado -> OrcamentoEventPayloads.registrarEvento(
                                    outboxEventSender,
                                    atualizado,
                                    "orcamentoRecusado",
                                    "oficina.billing.orcamento-recusado",
                                    "recusadoEm",
                                    atualizado.atualizadoEm(),
                                    command.motivo())
                                    .thenApply(ignored -> atualizado));
                });
    }

    private CompletableFuture<Orcamento> atualizarStatus(Orcamento orcamento, StatusOrcamento status) {
        var atualizado = new Orcamento(
                orcamento.orcamentoId(),
                orcamento.ordemServicoId(),
                orcamento.itens(),
                orcamento.valorTotal(),
                status,
                orcamento.criadoEm(),
                OffsetDateTime.now(clock));
        return repository.save(atualizado);
    }

    public record Command(UUID orcamentoId, String motivo) {
    }
}
