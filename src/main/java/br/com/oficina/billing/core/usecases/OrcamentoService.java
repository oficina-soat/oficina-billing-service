package br.com.oficina.billing.core.usecases;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.core.interfaces.OrcamentoRepository;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OrcamentoService {
    private final OrcamentoRepository repository;
    private final BillingEventStore eventStore;
    private final Clock clock;

    public OrcamentoService(OrcamentoRepository repository, BillingEventStore eventStore) {
        this.repository = repository;
        this.eventStore = eventStore;
        this.clock = Clock.systemUTC();
    }

    public Orcamento gerar(UUID ordemServicoId) {
        var now = OffsetDateTime.now(clock);
        var itens = eventStore.snapshotFinanceiro(ordemServicoId);
        if (itens.isEmpty()) {
            itens = List.of(new ItemOrcamento(
                    TipoItemOrcamento.SERVICO,
                    UUID.randomUUID(),
                    null,
                    "Snapshot financeiro inicial",
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }
        var valorTotal = itens.stream()
                .map(ItemOrcamento::valorTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var orcamento = new Orcamento(
                UUID.randomUUID(),
                ordemServicoId,
                itens,
                valorTotal,
                StatusOrcamento.GERADO,
                now,
                now);
        var salvo = repository.save(orcamento);
        registrarEvento(salvo, "orcamentoGerado", "oficina.billing.orcamento-gerado", "geradoEm", now, null);
        return salvo;
    }

    public Orcamento consultar(UUID orcamentoId) {
        return repository.findById(orcamentoId)
                .orElseThrow(() -> new ResourceNotFoundException("Orcamento nao encontrado."));
    }

    public List<Orcamento> consultarPorOrdemServico(UUID ordemServicoId) {
        return repository.findByOrdemServicoId(ordemServicoId);
    }

    public Orcamento aprovar(UUID orcamentoId, String motivo) {
        var orcamento = consultar(orcamentoId);
        if (orcamento.status() != StatusOrcamento.GERADO) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Somente orcamentos gerados podem ser aprovados.");
        }
        var atualizado = atualizarStatus(orcamento, StatusOrcamento.APROVADO);
        registrarEvento(atualizado, "orcamentoAprovado", "oficina.billing.orcamento-aprovado", "aprovadoEm", atualizado.atualizadoEm(), motivo);
        return atualizado;
    }

    public Orcamento recusar(UUID orcamentoId, String motivo) {
        var orcamento = consultar(orcamentoId);
        if (orcamento.status() != StatusOrcamento.GERADO) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Somente orcamentos gerados podem ser recusados.");
        }
        var atualizado = atualizarStatus(orcamento, StatusOrcamento.RECUSADO);
        registrarEvento(atualizado, "orcamentoRecusado", "oficina.billing.orcamento-recusado", "recusadoEm", atualizado.atualizadoEm(), motivo);
        return atualizado;
    }

    private Orcamento atualizarStatus(Orcamento orcamento, StatusOrcamento status) {
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

    private void registrarEvento(
            Orcamento orcamento,
            String eventType,
            String topic,
            String timestampField,
            OffsetDateTime ocorridoEm,
            String motivo) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("orcamentoId", orcamento.orcamentoId().toString());
        payload.put("ordemServicoId", orcamento.ordemServicoId().toString());
        if ("orcamentoGerado".equals(eventType)) {
            payload.put("itens", orcamento.itens().stream().map(this::itemPayload).toList());
        }
        payload.put("valorTotal", orcamento.valorTotal());
        payload.put("status", orcamento.status().name());
        if (motivo != null && !motivo.isBlank()) {
            payload.put("motivo", motivo);
        }
        payload.put(timestampField, ocorridoEm.toString());
        eventStore.registrarOutbox(orcamento.orcamentoId().toString(), eventType, topic, payload, null, ocorridoEm);
    }

    private Map<String, Object> itemPayload(ItemOrcamento item) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("tipo", item.tipo().name());
        payload.put("itemId", item.itemId().toString());
        if (item.referenciaCatalogoId() != null) {
            payload.put("referenciaCatalogoId", item.referenciaCatalogoId().toString());
        }
        payload.put("nome", item.nome());
        payload.put("quantidade", item.quantidade());
        payload.put("valorUnitario", item.valorUnitario());
        payload.put("valorTotal", item.valorTotal());
        return payload;
    }
}
