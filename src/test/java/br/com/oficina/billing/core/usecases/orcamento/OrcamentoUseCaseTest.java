package br.com.oficina.billing.core.usecases.orcamento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.exceptions.ResourceNotFoundException;
import br.com.oficina.billing.framework.db.InMemoryOrcamentoDataSourceAdapter;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class OrcamentoUseCaseTest {
    private final BillingEventStore eventStore = new BillingEventStore();
    private final InMemoryOrcamentoDataSourceAdapter repository = new InMemoryOrcamentoDataSourceAdapter();
    private final GerarOrcamentoUseCase gerarOrcamentoUseCase =
            new GerarOrcamentoUseCase(repository, eventStore, eventStore);
    private final AprovarOrcamentoUseCase aprovarOrcamentoUseCase =
            new AprovarOrcamentoUseCase(repository, eventStore);
    private final RecusarOrcamentoUseCase recusarOrcamentoUseCase =
            new RecusarOrcamentoUseCase(repository, eventStore);
    private final ConsultarOrcamentoUseCase consultarOrcamentoUseCase = new ConsultarOrcamentoUseCase(repository);

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

        var orcamento = gerar(ordemServicoId);

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
        var aprovado = aprovar(gerar(UUID.randomUUID()).orcamentoId(), "Cliente aprovou");

        assertEquals(StatusOrcamento.APROVADO, aprovado.status());
        var orcamentoAprovadoId = aprovado.orcamentoId();
        var erroAprovacaoRepetida = assertFutureThrows(
                BusinessException.class,
                () -> aprovarOrcamentoUseCase.executar(new AprovarOrcamentoUseCase.Command(orcamentoAprovadoId, null)));
        assertEquals("INVALID_STATE_TRANSITION", erroAprovacaoRepetida.code());

        var recusado = recusar(gerar(UUID.randomUUID()).orcamentoId(), "Cliente recusou");

        assertEquals(StatusOrcamento.RECUSADO, recusado.status());
        var orcamentoRecusadoId = recusado.orcamentoId();
        var erroRecusaRepetida = assertFutureThrows(
                BusinessException.class,
                () -> recusarOrcamentoUseCase.executar(new RecusarOrcamentoUseCase.Command(orcamentoRecusadoId, null)));
        assertEquals("INVALID_STATE_TRANSITION", erroRecusaRepetida.code());
    }

    @Test
    void deveFalharAoConsultarOrcamentoInexistente() {
        var orcamentoId = UUID.randomUUID();
        assertFutureThrows(
                ResourceNotFoundException.class,
                () -> consultarOrcamentoUseCase.executar(new ConsultarOrcamentoUseCase.Command(orcamentoId)));
    }

    private Orcamento gerar(UUID ordemServicoId) {
        return gerarOrcamentoUseCase.executar(new GerarOrcamentoUseCase.Command(ordemServicoId)).join();
    }

    private Orcamento aprovar(UUID orcamentoId, String motivo) {
        return aprovarOrcamentoUseCase.executar(new AprovarOrcamentoUseCase.Command(orcamentoId, motivo)).join();
    }

    private Orcamento recusar(UUID orcamentoId, String motivo) {
        return recusarOrcamentoUseCase.executar(new RecusarOrcamentoUseCase.Command(orcamentoId, motivo)).join();
    }

    private <T extends Throwable> T assertFutureThrows(
            Class<T> expectedType,
            Supplier<CompletableFuture<?>> executable) {
        var exception = assertThrows(CompletionException.class, () -> executable.get().join());
        return assertInstanceOf(expectedType, exception.getCause());
    }
}
