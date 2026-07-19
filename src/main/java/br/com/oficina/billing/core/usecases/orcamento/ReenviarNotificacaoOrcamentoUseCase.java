package br.com.oficina.billing.core.usecases.orcamento;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.sender.OrcamentoApprovalSender;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ReenviarNotificacaoOrcamentoUseCase {
    private final OrcamentoRepositoryGateway repository;
    private final OrcamentoApprovalSender approvalSender;

    public ReenviarNotificacaoOrcamentoUseCase(
            OrcamentoRepositoryGateway repository,
            OrcamentoApprovalSender approvalSender) {
        this.repository = repository;
        this.approvalSender = approvalSender;
    }

    public CompletableFuture<Orcamento> executar(Command command) {
        return repository.findById(command.orcamentoId())
                .thenCompose(optional -> {
                    var orcamento = optional.orElseThrow(() ->
                            new ResourceNotFoundException("Orcamento nao encontrado."));
                    if (orcamento.status() != StatusOrcamento.GERADO) {
                        throw new BusinessException(
                                "INVALID_STATE_TRANSITION",
                                "Somente orcamentos gerados podem ter a notificacao reenviada.");
                    }
                    return approvalSender.enviar(orcamento)
                            .thenApply(ignored -> orcamento)
                            .exceptionallyCompose(failure -> CompletableFuture.failedFuture(
                                    new BusinessException(
                                            "DEPENDENCY_UNAVAILABLE",
                                            "Nao foi possivel enviar o e-mail do orcamento. Tente novamente.")));
                });
    }

    public record Command(UUID orcamentoId) {
    }
}
