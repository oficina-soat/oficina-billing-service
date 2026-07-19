package br.com.oficina.billing.interfaces.controllers;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.TipoReferenciaExternaPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.usecases.pagamento.CancelarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConfirmarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConsultarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ConsultarPagamentosDaOrdemServicoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.RecusarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.RegistrarPagamentoUseCase;
import br.com.oficina.billing.core.usecases.pagamento.ReconciliarPagamentoUseCase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PagamentoController {
    private final RegistrarPagamentoUseCase registrarPagamentoUseCase;
    private final ConsultarPagamentoUseCase consultarPagamentoUseCase;
    private final ConsultarPagamentosDaOrdemServicoUseCase consultarPagamentosDaOrdemServicoUseCase;
    private final ConfirmarPagamentoUseCase confirmarPagamentoUseCase;
    private final RecusarPagamentoUseCase recusarPagamentoUseCase;
    private final CancelarPagamentoUseCase cancelarPagamentoUseCase;
    private final ReconciliarPagamentoUseCase reconciliarPagamentoUseCase;

    public PagamentoController(
            RegistrarPagamentoUseCase registrarPagamentoUseCase,
            ConsultarPagamentoUseCase consultarPagamentoUseCase,
            ConsultarPagamentosDaOrdemServicoUseCase consultarPagamentosDaOrdemServicoUseCase,
            ConfirmarPagamentoUseCase confirmarPagamentoUseCase,
            RecusarPagamentoUseCase recusarPagamentoUseCase,
            CancelarPagamentoUseCase cancelarPagamentoUseCase) {
        this(
                registrarPagamentoUseCase,
                consultarPagamentoUseCase,
                consultarPagamentosDaOrdemServicoUseCase,
                confirmarPagamentoUseCase,
                recusarPagamentoUseCase,
                cancelarPagamentoUseCase,
                null);
    }

    public PagamentoController(
            RegistrarPagamentoUseCase registrarPagamentoUseCase,
            ConsultarPagamentoUseCase consultarPagamentoUseCase,
            ConsultarPagamentosDaOrdemServicoUseCase consultarPagamentosDaOrdemServicoUseCase,
            ConfirmarPagamentoUseCase confirmarPagamentoUseCase,
            RecusarPagamentoUseCase recusarPagamentoUseCase,
            CancelarPagamentoUseCase cancelarPagamentoUseCase,
            ReconciliarPagamentoUseCase reconciliarPagamentoUseCase) {
        this.registrarPagamentoUseCase = registrarPagamentoUseCase;
        this.consultarPagamentoUseCase = consultarPagamentoUseCase;
        this.consultarPagamentosDaOrdemServicoUseCase = consultarPagamentosDaOrdemServicoUseCase;
        this.confirmarPagamentoUseCase = confirmarPagamentoUseCase;
        this.recusarPagamentoUseCase = recusarPagamentoUseCase;
        this.cancelarPagamentoUseCase = cancelarPagamentoUseCase;
        this.reconciliarPagamentoUseCase = reconciliarPagamentoUseCase;
    }

    public CompletableFuture<Pagamento> registrarPagamento(PagamentoCreateRequest request) {
        validar(request);
        return registrarPagamentoUseCase.executar(new RegistrarPagamentoUseCase.Command(
                request.ordemServicoId(),
                request.orcamentoId(),
                request.valor(),
                request.metodo()));
    }

    public CompletableFuture<Pagamento> consultarPagamento(UUID pagamentoId) {
        return consultarPagamentoUseCase.executar(new ConsultarPagamentoUseCase.Command(pagamentoId));
    }

    public CompletableFuture<List<Pagamento>> consultarPagamentosDaOrdemServico(UUID ordemServicoId) {
        return consultarPagamentosDaOrdemServicoUseCase.executar(
                new ConsultarPagamentosDaOrdemServicoUseCase.Command(ordemServicoId));
    }

    public CompletableFuture<Pagamento> confirmarPagamento(UUID pagamentoId, ConfirmacaoPagamentoRequest request) {
        return confirmarPagamentoUseCase.executar(new ConfirmarPagamentoUseCase.Command(
                pagamentoId,
                request == null ? null : request.provedor(),
                request == null ? null : request.transacaoExternaId()));
    }

    public CompletableFuture<Pagamento> recusarPagamento(UUID pagamentoId, RecusaPagamentoRequest request) {
        return recusarPagamentoUseCase.executar(new RecusarPagamentoUseCase.Command(
                pagamentoId,
                request == null ? null : request.provedor(),
                request == null ? null : request.motivo()));
    }

    public CompletableFuture<Pagamento> cancelarPagamento(UUID pagamentoId, CancelamentoRequest request) {
        return cancelarPagamentoUseCase.executar(new CancelarPagamentoUseCase.Command(
                pagamentoId,
                request == null ? null : request.motivo()));
    }

    public CompletableFuture<Pagamento> reconciliarPagamento(UUID pagamentoId) {
        return reconciliarPagamentoUseCase.executar(new ReconciliarPagamentoUseCase.Command(pagamentoId));
    }

    public CompletableFuture<Pagamento> reconciliarPagamentoPorTransacao(String transacaoExternaId) {
        return reconciliarPagamentoUseCase.executarPorTransacaoExternaId(transacaoExternaId);
    }

    public CompletableFuture<Pagamento> reconciliarPagamentoPorTransacao(
            String transacaoExternaId,
            TipoReferenciaExternaPagamento tipoReferenciaExterna) {
        return reconciliarPagamentoUseCase.executarPorTransacaoExternaId(
                transacaoExternaId,
                tipoReferenciaExterna);
    }

    private void validar(PagamentoCreateRequest request) {
        if (request == null
                || request.ordemServicoId() == null
                || request.orcamentoId() == null
                || request.valor() == null
                || request.metodo() == null) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "Campos ordemServicoId, orcamentoId, valor e metodo sao obrigatorios.");
        }
    }

    public record PagamentoCreateRequest(
            UUID ordemServicoId,
            UUID orcamentoId,
            BigDecimal valor,
            MetodoPagamento metodo) {
    }

    public record ConfirmacaoPagamentoRequest(String provedor, String transacaoExternaId) {
    }

    public record RecusaPagamentoRequest(String provedor, String motivo) {
    }

    public record CancelamentoRequest(String motivo) {
    }
}
