package br.com.oficina.billing.core.usecases.orcamento;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class OrcamentoEventPayloads {
    private OrcamentoEventPayloads() {
    }

    static CompletableFuture<Void> registrarEvento(
            OutboxEventSender outboxEventSender,
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
            payload.put("itens", orcamento.itens().stream().map(OrcamentoEventPayloads::itemPayload).toList());
        }
        payload.put("valorTotal", orcamento.valorTotal());
        payload.put("status", orcamento.status().name());
        if (motivo != null && !motivo.isBlank()) {
            payload.put("motivo", motivo);
        }
        payload.put(timestampField, ocorridoEm.toString());
        return outboxEventSender.registrarOutbox(
                orcamento.ordemServicoId().toString(),
                eventType,
                topic,
                payload,
                null,
                ocorridoEm);
    }

    private static Map<String, Object> itemPayload(ItemOrcamento item) {
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
