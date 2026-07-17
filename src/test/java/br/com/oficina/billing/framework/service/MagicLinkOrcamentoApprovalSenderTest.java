package br.com.oficina.billing.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.entities.StatusOrcamento;
import br.com.oficina.billing.framework.messaging.InMemoryBillingEventStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MagicLinkOrcamentoApprovalSenderTest {
    @Test
    void deveEmitirLinksDistintosParaAsTresCapacidades() {
        var store = new InMemoryBillingEventStore();
        var ordemServicoId = UUID.randomUUID();
        store.registrarContato(ordemServicoId, "cliente@oficina.test");
        var client = new CapturingClient();
        var sender = new MagicLinkOrcamentoApprovalSender(store, client, "https://api.oficina.test/", true);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var orcamento = new Orcamento(
                UUID.randomUUID(), ordemServicoId, List.of(), new BigDecimal("150.00"),
                StatusOrcamento.GERADO, now, now);

        sender.enviar(orcamento).join();

        assertEquals("cliente@oficina.test", client.request.emailDestino());
        assertTrue(client.request.conteudo().contains("/acompanhar-link?actionToken="));
        assertTrue(client.request.conteudo().contains("/aprovar-link?actionToken="));
        assertTrue(client.request.conteudo().contains("/recusar-link?actionToken="));
        assertFalse(client.request.conteudo().contains("null"));
    }

    static final class CapturingClient implements NotificacaoClient {
        private NotificacaoEmailRequest request;

        @Override
        public void enviarEmail(NotificacaoEmailRequest request) {
            this.request = request;
        }
    }
}
