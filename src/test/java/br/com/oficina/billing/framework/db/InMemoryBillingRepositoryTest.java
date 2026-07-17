package br.com.oficina.billing.framework.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.core.entities.StatusPagamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryBillingRepositoryTest {
    @Test
    void devePersistirEConsultarOrcamentosPorIdEOrdemServicoOrdenadosPorCriacao() {
        var repository = new InMemoryOrcamentoDataSourceAdapter();
        var ordemServicoId = UUID.randomUUID();
        var primeiro = orcamento(UUID.randomUUID(), ordemServicoId, "2026-06-23T10:00:00Z");
        var segundo = orcamento(UUID.randomUUID(), ordemServicoId, "2026-06-23T10:05:00Z");

        repository.save(segundo).join();
        repository.save(primeiro).join();

        assertEquals(primeiro, repository.findById(primeiro.orcamentoId()).join().orElseThrow());
        assertEquals(List.of(primeiro, segundo), repository.findByOrdemServicoId(ordemServicoId).join());
        assertEquals(List.of(primeiro, segundo), repository.findAll().join());
        assertTrue(repository.findById(UUID.randomUUID()).join().isEmpty());
    }

    @Test
    void devePersistirEConsultarPagamentosPorIdOrdemServicoEOrcamento() {
        var repository = new InMemoryPagamentoDataSourceAdapter();
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var pagamento = pagamento(UUID.randomUUID(), ordemServicoId, orcamentoId, "2026-06-23T10:00:00Z");

        repository.save(pagamento).join();

        assertEquals(pagamento, repository.findById(pagamento.pagamentoId()).join().orElseThrow());
        assertEquals(List.of(pagamento), repository.findByOrdemServicoId(ordemServicoId).join());
        assertEquals(List.of(pagamento), repository.findAll().join());
        assertEquals(pagamento, repository.findByOrcamentoId(orcamentoId).join().orElseThrow());
        assertTrue(repository.findByOrcamentoId(UUID.randomUUID()).join().isEmpty());
    }

    private Orcamento orcamento(UUID orcamentoId, UUID ordemServicoId, String criadoEm) {
        var data = OffsetDateTime.parse(criadoEm);
        return new Orcamento(
                orcamentoId,
                ordemServicoId,
                List.of(new ItemOrcamento(
                        TipoItemOrcamento.SERVICO,
                        UUID.randomUUID(),
                        null,
                        "Diagnostico",
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        BigDecimal.TEN)),
                BigDecimal.TEN,
                StatusOrcamento.GERADO,
                data,
                data);
    }

    private Pagamento pagamento(UUID pagamentoId, UUID ordemServicoId, UUID orcamentoId, String criadoEm) {
        var data = OffsetDateTime.parse(criadoEm).withOffsetSameInstant(ZoneOffset.UTC);
        return new Pagamento(
                pagamentoId,
                ordemServicoId,
                orcamentoId,
                BigDecimal.TEN,
                MetodoPagamento.PIX,
                StatusPagamento.CRIADO,
                null,
                null,
                data,
                data);
    }
}
