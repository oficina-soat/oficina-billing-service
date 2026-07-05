package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.usecases.OrcamentoService;
import br.com.oficina.billing.core.usecases.PagamentoService;
import br.com.oficina.billing.framework.db.InMemoryOrcamentoRepository;
import br.com.oficina.billing.framework.db.InMemoryPagamentoRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillingEventConsumerTest {
    private final BillingEventStore store = new BillingEventStore();
    private final InMemoryOrcamentoRepository orcamentoRepository = new InMemoryOrcamentoRepository();
    private final InMemoryPagamentoRepository pagamentoRepository = new InMemoryPagamentoRepository();
    private final OrcamentoService orcamentoService = new OrcamentoService(orcamentoRepository, store);
    private final PagamentoService pagamentoService = new PagamentoService(pagamentoRepository, orcamentoService, store);
    private final BillingEventConsumer consumer = new BillingEventConsumer(store, pagamentoService);
    private final OutboxPublisher publisher = new OutboxPublisher(store);

    @Test
    void deveGerarOrcamentoComSnapshotFinanceiroEPublicarEventos() {
        var ordemServicoId = UUID.randomUUID();
        var eventoDiagnostico = envelope(
                UUID.randomUUID(),
                "diagnosticoFinalizado",
                ordemServicoId,
                Map.of(
                        "ordemServicoId", ordemServicoId.toString(),
                        "pecas", List.of(Map.of(
                                "pecaId", UUID.randomUUID().toString(),
                                "nome", "Bateria 60Ah",
                                "quantidade", "1.0",
                                "valorUnitario", "320.00",
                                "valorTotal", "320.00")),
                        "servicos", List.of(Map.of(
                                "servicoId", UUID.randomUUID().toString(),
                                "nome", "Troca de bateria",
                                "quantidade", "1.0",
                                "valorUnitario", "80.00",
                                "valorTotal", "80.00"))));

        assertTrue(consumer.consumir(eventoDiagnostico));
        assertFalse(consumer.consumir(eventoDiagnostico));

        var orcamento = orcamentoService.gerar(ordemServicoId);

        assertEquals(new BigDecimal("400.00"), orcamento.valorTotal());
        assertEquals(2, orcamento.itens().size());
        assertTrue(store.listarOutbox().stream().anyMatch(event ->
                event.eventType().equals("orcamentoGerado")
                        && event.topic().equals("oficina.billing.orcamento-gerado")
                        && event.payload().get("valorTotal").equals(new BigDecimal("400.00"))));
    }

    @Test
    void deveSolicitarPagamentoQuandoOrdemForFinalizada() {
        var ordemServicoId = UUID.randomUUID();
        var orcamento = orcamentoService.gerar(ordemServicoId);
        var aprovado = orcamentoService.aprovar(orcamento.orcamentoId(), "Aprovado pelo cliente");

        assertEquals(StatusOrcamento.APROVADO, aprovado.status());

        var eventoFinalizacao = envelope(
                UUID.randomUUID(),
                "ordemDeServicoFinalizada",
                ordemServicoId,
                Map.of("ordemServicoId", ordemServicoId.toString()));

        assertTrue(consumer.consumir(eventoFinalizacao));
        assertFalse(consumer.consumir(eventoFinalizacao));

        var pagamentos = pagamentoService.consultarPorOrdemServico(ordemServicoId);
        assertEquals(1, pagamentos.size());
        assertEquals(StatusPagamento.CRIADO, pagamentos.getFirst().status());
        assertEquals(MetodoPagamento.PIX, pagamentos.getFirst().metodo());
        assertTrue(store.listarOutbox().stream().anyMatch(event ->
                event.eventType().equals("pagamentoSolicitado")
                        && event.topic().equals("oficina.billing.pagamento-solicitado")));
    }

    @Test
    void devePublicarEventosPendentesDaOutbox() {
        var ordemServicoId = UUID.randomUUID();
        var orcamento = orcamentoService.gerar(ordemServicoId);
        orcamentoService.aprovar(orcamento.orcamentoId(), null);
        var pagamento = pagamentoService.registrar(ordemServicoId, orcamento.orcamentoId(), BigDecimal.ZERO, MetodoPagamento.PIX);
        pagamentoService.confirmar(pagamento.pagamentoId(), "mercado-pago", "mp-test");

        var publicados = publisher.publicarPendentes();

        assertEquals(4, publicados.size());
        assertTrue(store.listarOutbox().stream().allMatch(event -> event.status().equals("PUBLISHED")));
        assertTrue(publicados.stream().anyMatch(event -> event.eventType().equals("pagamentoConfirmado")));
    }

    @Test
    void deveRejeitarEventoNaoContratadoParaConsumo() {
        var envelope = envelope(UUID.randomUUID(), "eventoInexistente", UUID.randomUUID(), Map.of());

        assertThrows(IllegalArgumentException.class, () -> consumer.consumir(envelope));
    }

    private DomainEventEnvelope envelope(UUID eventId, String eventType, UUID ordemServicoId, Map<String, Object> payload) {
        return new DomainEventEnvelope(
                eventId,
                eventType,
                1,
                OffsetDateTime.now(ZoneOffset.UTC),
                "oficina-os-service",
                ordemServicoId.toString(),
                payload);
    }
}
