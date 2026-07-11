package br.com.oficina.billing.interfaces.controllers;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.usecases.orcamento.AprovarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.ConsultarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.ConsultarOrcamentosDaOrdemServicoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.GerarOrcamentoUseCase;
import br.com.oficina.billing.core.usecases.orcamento.RecusarOrcamentoUseCase;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class OrcamentoController {
    private final GerarOrcamentoUseCase gerarOrcamentoUseCase;
    private final ConsultarOrcamentoUseCase consultarOrcamentoUseCase;
    private final ConsultarOrcamentosDaOrdemServicoUseCase consultarOrcamentosDaOrdemServicoUseCase;
    private final AprovarOrcamentoUseCase aprovarOrcamentoUseCase;
    private final RecusarOrcamentoUseCase recusarOrcamentoUseCase;

    public OrcamentoController(
            GerarOrcamentoUseCase gerarOrcamentoUseCase,
            ConsultarOrcamentoUseCase consultarOrcamentoUseCase,
            ConsultarOrcamentosDaOrdemServicoUseCase consultarOrcamentosDaOrdemServicoUseCase,
            AprovarOrcamentoUseCase aprovarOrcamentoUseCase,
            RecusarOrcamentoUseCase recusarOrcamentoUseCase) {
        this.gerarOrcamentoUseCase = gerarOrcamentoUseCase;
        this.consultarOrcamentoUseCase = consultarOrcamentoUseCase;
        this.consultarOrcamentosDaOrdemServicoUseCase = consultarOrcamentosDaOrdemServicoUseCase;
        this.aprovarOrcamentoUseCase = aprovarOrcamentoUseCase;
        this.recusarOrcamentoUseCase = recusarOrcamentoUseCase;
    }

    public CompletableFuture<Orcamento> gerarOrcamento(GerarOrcamentoRequest request) {
        validar(request);
        return gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(request.ordemServicoId()));
    }

    public CompletableFuture<Orcamento> consultarOrcamento(UUID orcamentoId) {
        return consultarOrcamentoUseCase.executar(new ConsultarOrcamentoUseCase.Command(orcamentoId));
    }

    public CompletableFuture<List<Orcamento>> consultarOrcamentosDaOrdemServico(UUID ordemServicoId) {
        return consultarOrcamentosDaOrdemServicoUseCase.executar(
                new ConsultarOrcamentosDaOrdemServicoUseCase.Command(ordemServicoId));
    }

    public CompletableFuture<Orcamento> aprovarOrcamento(UUID orcamentoId, DecisaoOrcamentoRequest request) {
        return aprovarOrcamentoUseCase.executar(new AprovarOrcamentoUseCase.Command(
                orcamentoId,
                request == null ? null : request.motivo()));
    }

    public CompletableFuture<Orcamento> recusarOrcamento(UUID orcamentoId, DecisaoOrcamentoRequest request) {
        return recusarOrcamentoUseCase.executar(new RecusarOrcamentoUseCase.Command(
                orcamentoId,
                request == null ? null : request.motivo()));
    }

    private void validar(GerarOrcamentoRequest request) {
        if (request == null || request.ordemServicoId() == null) {
            throw new BusinessException("VALIDATION_ERROR", "Campo ordemServicoId e obrigatorio.");
        }
    }

    public record GerarOrcamentoRequest(UUID ordemServicoId) {
    }

    public record DecisaoOrcamentoRequest(String motivo) {
    }
}
