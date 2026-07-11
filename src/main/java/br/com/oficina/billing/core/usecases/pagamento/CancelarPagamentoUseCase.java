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
    private static final int TAMANHO_MAXIMO_MOTIVO = 200;

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
        validarMotivo(command.motivo());
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

    private static void validarMotivo(String motivo) {
        if (motivo != null && motivo.trim().length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "Motivo do cancelamento deve ter no maximo 200 caracteres.");
        }
    }

    public record Command(UUID pagamentoId, String motivo) {
        public Command(UUID pagamentoId) {
            this(pagamentoId, null);
        }
    }
}
