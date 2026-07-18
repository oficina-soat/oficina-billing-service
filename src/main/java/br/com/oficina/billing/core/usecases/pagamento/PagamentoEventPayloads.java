package br.com.oficina.billing.core.usecases.pagamento;

import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class PagamentoEventPayloads {
    static final Evento PAGAMENTO_SOLICITADO = new Evento(
            "pagamentoSolicitado",
            "oficina.billing.pagamento-solicitado",
            "solicitadoEm");
    static final Evento PAGAMENTO_CONFIRMADO = new Evento(
            "pagamentoConfirmado",
            "oficina.billing.pagamento-confirmado",
            "confirmadoEm");
    static final Evento PAGAMENTO_RECUSADO = new Evento(
            "pagamentoRecusado",
            "oficina.billing.pagamento-recusado",
            "recusadoEm");

    private PagamentoEventPayloads() {
    }

    static CompletableFuture<Void> registrarEvento(Registro registro) {
        return registro.outboxEventSender().registrarOutbox(
                registro.pagamento().pagamentoId().toString(),
                registro.evento().eventType(),
                registro.evento().topic(),
                payload(registro),
                null,
                registro.ocorridoEm());
    }

    static CompletableFuture<Void> registrarEventoIdempotente(UUID eventId, Registro registro) {
        return registro.outboxEventSender().registrarOutboxIdempotente(
                eventId,
                registro.pagamento().pagamentoId().toString(),
                registro.evento().eventType(),
                registro.evento().topic(),
                payload(registro),
                null,
                registro.ocorridoEm());
    }

    private static LinkedHashMap<String, Object> payload(Registro registro) {
        var payload = new LinkedHashMap<String, Object>();
        var pagamento = registro.pagamento();
        payload.put("pagamentoId", pagamento.pagamentoId().toString());
        payload.put("ordemServicoId", pagamento.ordemServicoId().toString());
        payload.put("orcamentoId", pagamento.orcamentoId().toString());
        payload.put("valor", pagamento.valor());
        var evento = registro.evento();
        if (PAGAMENTO_SOLICITADO.eventType().equals(evento.eventType())) {
            payload.put("metodo", pagamento.metodo().name());
        }
        payload.put("status", pagamento.status().name());
        var provedorEvento = registro.provedor() == null ? pagamento.provedor() : registro.provedor();
        if (provedorEvento != null && !provedorEvento.isBlank()) {
            payload.put("provedor", provedorEvento);
        }
        if (pagamento.transacaoExternaId() != null && !pagamento.transacaoExternaId().isBlank()) {
            payload.put("transacaoExternaId", pagamento.transacaoExternaId());
        }
        if (registro.motivo() != null && !registro.motivo().isBlank()) {
            payload.put("motivo", registro.motivo());
        }
        payload.put(evento.timestampField(), registro.ocorridoEm().toString());
        return payload;
    }

    record Evento(String eventType, String topic, String timestampField) {
    }

    record Registro(
            OutboxEventSender outboxEventSender,
            Pagamento pagamento,
            Evento evento,
            OffsetDateTime ocorridoEm,
            String provedor,
            String motivo) {
    }
}
