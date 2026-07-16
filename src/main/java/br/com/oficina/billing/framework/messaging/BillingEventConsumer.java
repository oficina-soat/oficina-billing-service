package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.core.usecases.pagamento.CancelarPagamentosCriadosDaOrdemUseCase;
import br.com.oficina.billing.core.usecases.pagamento.SolicitarPagamentoDaOrdemUseCase;
import br.com.oficina.billing.core.usecases.orcamento.GerarOrcamentoUseCase;
import br.com.oficina.billing.framework.observability.StructuredLog;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class BillingEventConsumer {
    private static final Logger LOG = Logger.getLogger(BillingEventConsumer.class);
    private static final String CONSUMER = "oficina-billing-service";
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
    private final GerarOrcamentoUseCase gerarOrcamentoUseCase;
    private final SolicitarPagamentoDaOrdemUseCase solicitarPagamentoDaOrdemUseCase;
    private final CancelarPagamentosCriadosDaOrdemUseCase cancelarPagamentosCriadosDaOrdemUseCase;

    public BillingEventConsumer(
            BillingEventStore store,
            GerarOrcamentoUseCase gerarOrcamentoUseCase,
            SolicitarPagamentoDaOrdemUseCase solicitarPagamentoDaOrdemUseCase,
            CancelarPagamentosCriadosDaOrdemUseCase cancelarPagamentosCriadosDaOrdemUseCase) {
        this.store = store;
        this.gerarOrcamentoUseCase = gerarOrcamentoUseCase;
        this.solicitarPagamentoDaOrdemUseCase = solicitarPagamentoDaOrdemUseCase;
        this.cancelarPagamentosCriadosDaOrdemUseCase = cancelarPagamentosCriadosDaOrdemUseCase;
    }

    public boolean consumir(DomainEventEnvelope envelope) {
        if (!EVENTOS_CONSUMIDOS.contains(envelope.eventType())) {
            throw new IllegalArgumentException("Evento nao consumido pelo oficina-billing-service: " + envelope.eventType());
        }
        var correlationId = correlationId(envelope);
        if (store.eventoConsumido(envelope.eventId())) {
            logEvent("domain event ignored", envelope, "DUPLICATE", correlationId);
            return false;
        }
        StructuredLog.withFields(eventFields(envelope, "PROCESSING", correlationId), () -> {
            aplicarEvento(envelope);
            store.registrarEventoConsumido(envelope);
        });
        logEvent("domain event consumed", envelope, "CONSUMED", correlationId);
        return true;
    }

    private void aplicarEvento(DomainEventEnvelope envelope) {
        switch (envelope.eventType()) {
            case "pecaIncluidaNaOrdemDeServico" -> registrarPeca(envelope);
            case "servicoIncluidoNaOrdemDeServico" -> registrarServico(envelope);
            case "diagnosticoFinalizado" -> {
                registrarDiagnostico(envelope);
                gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(
                        ordemServicoId(envelope), correlationId(envelope))).join();
            }
            case "ordemDeServicoFinalizada", "execucaoFinalizada" ->
                    solicitarPagamentoDaOrdemUseCase.executar(
                            new SolicitarPagamentoDaOrdemUseCase.Command(ordemServicoId(envelope))).join();
            case "sagaCompensada" ->
                    cancelarPagamentosCriadosDaOrdemUseCase.executar(
                            new CancelarPagamentosCriadosDaOrdemUseCase.Command(ordemServicoId(envelope))).join();
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

    private void logEvent(String message, DomainEventEnvelope envelope, String messageStatus, String correlationId) {
        StructuredLog.info(LOG, message, eventFields(envelope, messageStatus, correlationId));
    }

    private Map<String, Object> eventFields(DomainEventEnvelope envelope, String messageStatus, String correlationId) {
        return Map.of(
                "correlationId", correlationId,
                "eventId", envelope.eventId().toString(),
                "eventType", envelope.eventType(),
                "eventVersion", envelope.eventVersion(),
                "producer", envelope.producer(),
                "consumer", CONSUMER,
                "aggregateId", envelope.aggregateId(),
                "messageStatus", messageStatus);
    }

    private String correlationId(DomainEventEnvelope envelope) {
        return envelope.correlationId() == null || envelope.correlationId().isBlank()
                ? envelope.eventId().toString()
                : envelope.correlationId();
    }
}
