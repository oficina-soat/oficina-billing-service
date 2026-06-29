package br.com.oficina.billing.core.usecases;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.PagamentoRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PagamentoService {
    private final PagamentoRepository repository;
    private final OrcamentoService orcamentoService;
    private final Clock clock;

    public PagamentoService(PagamentoRepository repository, OrcamentoService orcamentoService) {
        this.repository = repository;
        this.orcamentoService = orcamentoService;
        this.clock = Clock.systemUTC();
    }

    public Pagamento registrar(UUID ordemServicoId, UUID orcamentoId, BigDecimal valor, MetodoPagamento metodo) {
        var orcamento = orcamentoService.consultar(orcamentoId);
        if (!orcamento.ordemServicoId().equals(ordemServicoId)) {
            throw new BusinessException("BUSINESS_RULE_VIOLATION", "Orcamento nao pertence a ordem de servico informada.");
        }
        if (orcamento.status() != StatusOrcamento.APROVADO) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Somente orcamentos aprovados podem receber pagamento.");
        }
        if (valor.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("VALIDATION_ERROR", "Valor do pagamento nao pode ser negativo.");
        }

        var now = OffsetDateTime.now(clock);
        var pagamento = new Pagamento(
                UUID.randomUUID(),
                ordemServicoId,
                orcamentoId,
                valor,
                metodo,
                StatusPagamento.CRIADO,
                null,
                null,
                now,
                now);
        return repository.save(pagamento);
    }

    public Pagamento consultar(UUID pagamentoId) {
        return repository.findById(pagamentoId)
                .orElseThrow(() -> new ResourceNotFoundException("Pagamento nao encontrado."));
    }

    public List<Pagamento> consultarPorOrdemServico(UUID ordemServicoId) {
        return repository.findByOrdemServicoId(ordemServicoId);
    }

    public Pagamento confirmar(UUID pagamentoId, String provedor, String transacaoExternaId) {
        var pagamento = consultar(pagamentoId);
        validarCriado(pagamento, "Somente pagamentos criados podem ser confirmados.");
        return atualizarStatus(pagamento, StatusPagamento.CONFIRMADO, provedor, transacaoExternaId);
    }

    public Pagamento recusar(UUID pagamentoId, String provedor) {
        var pagamento = consultar(pagamentoId);
        validarCriado(pagamento, "Somente pagamentos criados podem ser recusados.");
        return atualizarStatus(pagamento, StatusPagamento.RECUSADO, provedor, pagamento.transacaoExternaId());
    }

    public Pagamento cancelar(UUID pagamentoId) {
        var pagamento = consultar(pagamentoId);
        validarCriado(pagamento, "Somente pagamentos criados podem ser cancelados.");
        return atualizarStatus(pagamento, StatusPagamento.CANCELADO, pagamento.provedor(), pagamento.transacaoExternaId());
    }

    private void validarCriado(Pagamento pagamento, String message) {
        if (pagamento.status() != StatusPagamento.CRIADO) {
            throw new BusinessException("INVALID_STATE_TRANSITION", message);
        }
    }

    private Pagamento atualizarStatus(Pagamento pagamento, StatusPagamento status, String provedor, String transacaoExternaId) {
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
