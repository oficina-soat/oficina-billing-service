package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;

final class PagamentoEventPayloads {
    private PagamentoEventPayloads() {
    }

    static CompletableFuture<Void> registrarEvento(
            OutboxEventSender outboxEventSender,
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
        return outboxEventSender.registrarOutbox(
                pagamento.pagamentoId().toString(),
                eventType,
                topic,
                payload,
                null,
                ocorridoEm);
    }
}
