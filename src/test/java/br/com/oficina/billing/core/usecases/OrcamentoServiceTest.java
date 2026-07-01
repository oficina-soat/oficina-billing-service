package br.com.oficina.billing.core.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.framework.db.InMemoryOrcamentoRepository;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrcamentoServiceTest {
    private final BillingEventStore eventStore = new BillingEventStore();
    private final OrcamentoService service = new OrcamentoService(new InMemoryOrcamentoRepository(), eventStore);

    @Test
    void deveGerarOrcamentoComSnapshotFinanceiroEOutboxContratada() {
        var ordemServicoId = UUID.randomUUID();
        var servicoId = UUID.randomUUID();
        eventStore.registrarItem(ordemServicoId, new ItemOrcamento(
                TipoItemOrcamento.SERVICO,
                servicoId,
                servicoId,
                "Troca de bateria",
                BigDecimal.ONE,
                new BigDecimal("80.00"),
                new BigDecimal("80.00")));

        var orcamento = service.gerar(ordemServicoId);

        assertEquals(StatusOrcamento.GERADO, orcamento.status());
        assertEquals(new BigDecimal("80.00"), orcamento.valorTotal());
        assertEquals(1, orcamento.itens().size());
        assertTrue(eventStore.listarOutbox().stream().anyMatch(event ->
                event.eventType().equals("orcamentoGerado")
                        && event.topic().equals("oficina.billing.orcamento-gerado")
                        && event.aggregateId().equals(orcamento.orcamentoId().toString())));
    }

    @Test
    void deveAprovarERecusarSomenteOrcamentosGerados() {
        var aprovado = service.aprovar(service.gerar(UUID.randomUUID()).orcamentoId(), "Cliente aprovou");

        assertEquals(StatusOrcamento.APROVADO, aprovado.status());
        var erroAprovacaoRepetida = assertThrows(BusinessException.class, () -> service.aprovar(aprovado.orcamentoId(), null));
        assertEquals("INVALID_STATE_TRANSITION", erroAprovacaoRepetida.code());

        var recusado = service.recusar(service.gerar(UUID.randomUUID()).orcamentoId(), "Cliente recusou");

        assertEquals(StatusOrcamento.RECUSADO, recusado.status());
        var erroRecusaRepetida = assertThrows(BusinessException.class, () -> service.recusar(recusado.orcamentoId(), null));
        assertEquals("INVALID_STATE_TRANSITION", erroRecusaRepetida.code());
    }

    @Test
    void deveFalharAoConsultarOrcamentoInexistente() {
        assertThrows(ResourceNotFoundException.class, () -> service.consultar(UUID.randomUUID()));
    }
}
