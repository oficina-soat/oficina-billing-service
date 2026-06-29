package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.core.usecases.PagamentoService;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class BillingEventConsumer {
    private static final Set<String> EVENTOS_CONSUMIDOS = Set.of(
            "ordemDeServicoCriada",
            "pecaIncluidaNaOrdemDeServico",
            "servicoIncluidoNaOrdemDeServico",
            "diagnosticoFinalizado",
            "execucaoFinalizada",
            "ordemDeServicoFinalizada",
            "ordemDeServicoEntregue",
            "estoqueAcrescentado",
            "estoqueBaixado",
            "sagaCompensada",
            "sagaFinalizadaComSucesso");

    private final BillingEventStore store;
    private final PagamentoService pagamentoService;

    public BillingEventConsumer(BillingEventStore store, PagamentoService pagamentoService) {
        this.store = store;
        this.pagamentoService = pagamentoService;
    }

    public boolean consumir(DomainEventEnvelope envelope) {
        if (!EVENTOS_CONSUMIDOS.contains(envelope.eventType())) {
            throw new IllegalArgumentException("Evento nao consumido pelo oficina-billing-service: " + envelope.eventType());
        }
        if (store.eventoConsumido(envelope.eventId())) {
            return false;
        }
        aplicarEvento(envelope);
        store.registrarEventoConsumido(envelope.eventId());
        return true;
    }

    private void aplicarEvento(DomainEventEnvelope envelope) {
        switch (envelope.eventType()) {
            case "pecaIncluidaNaOrdemDeServico" -> registrarPeca(envelope);
            case "servicoIncluidoNaOrdemDeServico" -> registrarServico(envelope);
            case "diagnosticoFinalizado" -> registrarDiagnostico(envelope);
            case "ordemDeServicoFinalizada", "execucaoFinalizada" -> pagamentoService.solicitarPagamentoDaOrdem(ordemServicoId(envelope));
            case "sagaCompensada" -> pagamentoService.cancelarPagamentosCriadosDaOrdem(ordemServicoId(envelope));
            case "ordemDeServicoCriada", "ordemDeServicoEntregue", "estoqueAcrescentado", "estoqueBaixado", "sagaFinalizadaComSucesso" -> {
                // Eventos registrados apenas para idempotencia e auditoria local neste incremento.
            }
            default -> throw new IllegalArgumentException("Evento nao suportado: " + envelope.eventType());
        }
    }

    @SuppressWarnings("unchecked")
    private void registrarPeca(DomainEventEnvelope envelope) {
        store.registrarItem(ordemServicoId(envelope), store.itemPeca((Map<String, Object>) envelope.payload().get("peca")));
    }

    @SuppressWarnings("unchecked")
    private void registrarServico(DomainEventEnvelope envelope) {
        store.registrarItem(ordemServicoId(envelope), store.itemServico((Map<String, Object>) envelope.payload().get("servico")));
    }

    @SuppressWarnings("unchecked")
    private void registrarDiagnostico(DomainEventEnvelope envelope) {
        var ordemServicoId = ordemServicoId(envelope);
        for (var peca : (List<Map<String, Object>>) envelope.payload().getOrDefault("pecas", List.of())) {
            store.registrarItem(ordemServicoId, store.itemPeca(peca));
        }
        for (var servico : (List<Map<String, Object>>) envelope.payload().getOrDefault("servicos", List.of())) {
            store.registrarItem(ordemServicoId, store.itemServico(servico));
        }
    }

    private UUID ordemServicoId(DomainEventEnvelope envelope) {
        var payloadValue = envelope.payload().get("ordemServicoId");
        return payloadValue == null ? UUID.fromString(envelope.aggregateId()) : toUuid(payloadValue);
    }

    private UUID toUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
