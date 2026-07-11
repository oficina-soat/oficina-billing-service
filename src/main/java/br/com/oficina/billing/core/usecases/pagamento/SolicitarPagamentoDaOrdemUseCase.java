package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.gateway.OrcamentoRepositoryGateway;
import br.com.oficina.billing.core.interfaces.gateway.PagamentoRepositoryGateway;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SolicitarPagamentoDaOrdemUseCase {
    private final OrcamentoRepositoryGateway orcamentoRepository;
    private final PagamentoRepositoryGateway pagamentoRepository;
    private final RegistrarPagamentoUseCase registrarPagamentoUseCase;

    public SolicitarPagamentoDaOrdemUseCase(
            OrcamentoRepositoryGateway orcamentoRepository,
            PagamentoRepositoryGateway pagamentoRepository,
            RegistrarPagamentoUseCase registrarPagamentoUseCase) {
        this.orcamentoRepository = orcamentoRepository;
        this.pagamentoRepository = pagamentoRepository;
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
                    return pagamentoRepository.findByOrcamentoId(orcamento.orcamentoId())
                            .thenCompose(optional -> optional
                                    .map(CompletableFuture::completedFuture)
                                    .orElseGet(() -> registrarPagamentoUseCase.executar(
                                            new RegistrarPagamentoUseCase.Command(
                                                    command.ordemServicoId(),
                                                    orcamento.orcamentoId(),
                                                    orcamento.valorTotal(),
                                                    MetodoPagamento.PIX))));
                });
    }

    public record Command(UUID ordemServicoId) {
    }
}
