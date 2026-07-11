package br.com.oficina.billing.core.usecases;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.PagamentoGateway;
import br.com.oficina.billing.core.interfaces.PagamentoGatewayResult;
import br.com.oficina.billing.core.interfaces.PagamentoRepository;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PagamentoService {
    private final PagamentoRepository repository;
    private final OrcamentoService orcamentoService;
    private final BillingEventStore eventStore;
    private final PagamentoGateway pagamentoGateway;
    private final Clock clock;

    @Inject
    public PagamentoService(
            PagamentoRepository repository,
            OrcamentoService orcamentoService,
            BillingEventStore eventStore,
            PagamentoGateway pagamentoGateway) {
        this.repository = repository;
        this.orcamentoService = orcamentoService;
        this.eventStore = eventStore;
        this.pagamentoGateway = pagamentoGateway;
        this.clock = Clock.systemUTC();
    }

    public PagamentoService(PagamentoRepository repository, OrcamentoService orcamentoService, BillingEventStore eventStore) {
        this(repository, orcamentoService, eventStore, ignored -> PagamentoGatewayResult.naoIntegrado());
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
        var resultadoGateway = pagamentoGateway.solicitar(pagamento);
        var salvo = repository.save(pagamento);
        registrarEvento(salvo, "pagamentoSolicitado", "oficina.billing.pagamento-solicitado", "solicitadoEm", now, null, null);
        return aplicarResultadoGateway(salvo, resultadoGateway);
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
        var atualizado = atualizarStatus(pagamento, StatusPagamento.CONFIRMADO, provedor, transacaoExternaId);
        registrarEvento(atualizado, "pagamentoConfirmado", "oficina.billing.pagamento-confirmado", "confirmadoEm", atualizado.atualizadoEm(), null, null);
        return atualizado;
    }

    public Pagamento recusar(UUID pagamentoId, String provedor, String motivo) {
        var pagamento = consultar(pagamentoId);
        validarCriado(pagamento, "Somente pagamentos criados podem ser recusados.");
        var atualizado = atualizarStatus(pagamento, StatusPagamento.RECUSADO, provedor, pagamento.transacaoExternaId());
        registrarEvento(atualizado, "pagamentoRecusado", "oficina.billing.pagamento-recusado", "recusadoEm", atualizado.atualizadoEm(), provedor, motivo);
        return atualizado;
    }

    public Pagamento cancelar(UUID pagamentoId) {
        var pagamento = consultar(pagamentoId);
        validarCriado(pagamento, "Somente pagamentos criados podem ser cancelados.");
        return atualizarStatus(pagamento, StatusPagamento.CANCELADO, pagamento.provedor(), pagamento.transacaoExternaId());
    }

    public Pagamento solicitarPagamentoDaOrdem(UUID ordemServicoId) {
        var orcamento = orcamentoService.consultarPorOrdemServico(ordemServicoId).stream()
                .filter(item -> item.status() == StatusOrcamento.APROVADO)
                .max(Comparator.comparing(Orcamento::atualizadoEm))
                .orElseThrow(() -> new ResourceNotFoundException("Orcamento aprovado nao encontrado para a ordem de servico."));
        return repository.findByOrcamentoId(orcamento.orcamentoId())
                .orElseGet(() -> registrar(ordemServicoId, orcamento.orcamentoId(), orcamento.valorTotal(), MetodoPagamento.PIX));
    }

    public List<Pagamento> cancelarPagamentosCriadosDaOrdem(UUID ordemServicoId) {
        return repository.findByOrdemServicoId(ordemServicoId).stream()
                .filter(pagamento -> pagamento.status() == StatusPagamento.CRIADO)
                .map(pagamento -> atualizarStatus(pagamento, StatusPagamento.CANCELADO, pagamento.provedor(), pagamento.transacaoExternaId()))
                .toList();
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

    private Pagamento aplicarResultadoGateway(Pagamento pagamento, PagamentoGatewayResult resultado) {
        if (resultado == null || !resultado.integrado()) {
            return pagamento;
        }

        var atualizado = atualizarStatus(
                pagamento,
                resultado.status(),
                resultado.provedor(),
                resultado.transacaoExternaId());
        if (resultado.status() == StatusPagamento.CONFIRMADO) {
            registrarEvento(
                    atualizado,
                    "pagamentoConfirmado",
                    "oficina.billing.pagamento-confirmado",
                    "confirmadoEm",
                    atualizado.atualizadoEm(),
                    resultado.provedor(),
                    null);
        } else if (resultado.status() == StatusPagamento.RECUSADO) {
            registrarEvento(
                    atualizado,
                    "pagamentoRecusado",
                    "oficina.billing.pagamento-recusado",
                    "recusadoEm",
                    atualizado.atualizadoEm(),
                    resultado.provedor(),
                    resultado.motivo());
        }
        return atualizado;
    }

    private void registrarEvento(
            Pagamento pagamento,
            String eventType,
            String topic,
            String timestampField,
            OffsetDateTime ocorridoEm,
            String provedor,
            String motivo) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("pagamentoId", pagamento.pagamentoId().toString());
        payload.put("ordemServicoId", pagamento.ordemServicoId().toString());
        payload.put("orcamentoId", pagamento.orcamentoId().toString());
        payload.put("valor", pagamento.valor());
        if ("pagamentoSolicitado".equals(eventType)) {
            payload.put("metodo", pagamento.metodo().name());
        }
        payload.put("status", pagamento.status().name());
        var provedorEvento = provedor == null ? pagamento.provedor() : provedor;
        if (provedorEvento != null && !provedorEvento.isBlank()) {
            payload.put("provedor", provedorEvento);
        }
        if (pagamento.transacaoExternaId() != null && !pagamento.transacaoExternaId().isBlank()) {
            payload.put("transacaoExternaId", pagamento.transacaoExternaId());
        }
        if (motivo != null && !motivo.isBlank()) {
            payload.put("motivo", motivo);
        }
        payload.put(timestampField, ocorridoEm.toString());
        eventStore.registrarOutbox(pagamento.pagamentoId().toString(), eventType, topic, payload, null, ocorridoEm);
    }
}
