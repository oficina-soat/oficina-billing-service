package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillingEventStoreDefaultMethodsTest {
    private final BillingEventStore store = new InMemoryBillingEventStore();

    @Test
    void deveConverterItemDePecaComValoresTipadosOuTexto() {
        var pecaId = UUID.randomUUID();

        var item = store.itemPeca(Map.of(
                "pecaId", pecaId,
                "nome", "Filtro",
                "quantidade", BigDecimal.valueOf(2),
                "valorUnitario", "15.50",
                "valorTotal", "31.00"));

        assertEquals(TipoItemOrcamento.PECA, item.tipo());
        assertEquals(pecaId, item.itemId());
        assertEquals(new BigDecimal("31.00"), item.valorTotal());
    }

    @Test
    void deveConverterItemDeServicoComFallbackDeNome() {
        var servicoId = UUID.randomUUID();

        var item = store.itemServico(Map.of(
                "servicoId", servicoId.toString(),
                "nome", " ",
                "quantidade", "1",
                "valorUnitario", "100.00",
                "valorTotal", "100.00"));

        assertEquals(TipoItemOrcamento.SERVICO, item.tipo());
        assertEquals("Servico sem nome", item.nome());
    }

    @Test
    void deveRejeitarPayloadSemCamposObrigatorios() {
        var payloadPeca = Map.<String, Object>of("nome", "Filtro");
        var payloadServico = Map.<String, Object>of(
                "servicoId", UUID.randomUUID(),
                "nome", "Servico",
                "valorUnitario", "10.00",
                "valorTotal", "10.00");

        assertThrows(IllegalArgumentException.class, () -> store.itemPeca(payloadPeca));
        assertThrows(IllegalArgumentException.class, () -> store.itemServico(payloadServico));
    }
}
