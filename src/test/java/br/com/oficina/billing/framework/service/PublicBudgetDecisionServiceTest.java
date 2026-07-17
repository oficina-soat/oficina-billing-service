package br.com.oficina.billing.framework.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.framework.messaging.ApprovalTokenRecord;
import br.com.oficina.billing.framework.messaging.InMemoryBillingEventStore;
import br.com.oficina.billing.interfaces.controllers.OrcamentoController;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PublicBudgetDecisionServiceTest {
    @Test
    void deveLiberarTokenQuandoControllerFalharAntesDeCriarOperacaoAssincrona() throws Exception {
        var store = new InMemoryBillingEventStore();
        var ordemServicoId = UUID.randomUUID();
        var orcamentoId = UUID.randomUUID();
        var rawToken = "token-falha-sincrona";
        var tokenHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        store.substituirTokensAprovacao(ordemServicoId, orcamentoId, "cliente@oficina.test", List.of(
                new ApprovalTokenRecord(UUID.randomUUID(), tokenHash, "APROVAR", now, now.plusHours(1))));
        var service = new PublicBudgetDecisionService(store, new FailingOrcamentoController());

        assertThrows(IllegalStateException.class,
                () -> service.decidir(ordemServicoId, rawToken, "APROVAR", "Decisão sentinela"));

        assertTrue(store.buscarTokenAprovacao(tokenHash).orElseThrow().disponivelEm(now.plusMinutes(1)));
    }

    private static final class FailingOrcamentoController extends OrcamentoController {
        private FailingOrcamentoController() {
            super(null, null, null, null, null);
        }

        @Override
        public CompletableFuture<Orcamento> aprovarOrcamento(
                UUID orcamentoId,
                DecisaoOrcamentoRequest request) {
            throw new IllegalStateException("Falha síncrona sentinela");
        }
    }
}
