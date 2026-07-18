package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SolicitarPagamentoDaOrdemUseCase {
    private final OrcamentoRepositoryGateway orcamentoRepository;
    private final RegistrarPagamentoUseCase registrarPagamentoUseCase;

    public SolicitarPagamentoDaOrdemUseCase(
            OrcamentoRepositoryGateway orcamentoRepository,
            RegistrarPagamentoUseCase registrarPagamentoUseCase) {
        this.orcamentoRepository = orcamentoRepository;
        this.registrarPagamentoUseCase = registrarPagamentoUseCase;
    }

    public CompletableFuture<Pagamento> executar(Command command) {
        return orcamentoRepository.findByOrdemServicoId(command.ordemServicoId())
                .thenCompose(orcamentos -> {
                    var orcamento = orcamentos.stream()
                            .filter(item -> item.status() == StatusOrcamento.APROVADO)
                            .max(Comparator.comparing(Orcamento::atualizadoEm))
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "Orcamento aprovado nao encontrado para a ordem de servico."));
                    return registrarPagamentoUseCase.executarIdempotente(
                            new RegistrarPagamentoUseCase.Command(
                                    command.ordemServicoId(),
                                    orcamento.orcamentoId(),
                                    orcamento.valorTotal(),
                                    MetodoPagamento.PIX),
                            orcamento.orcamentoId());
                });
    }

    public record Command(UUID ordemServicoId) {
    }
}
