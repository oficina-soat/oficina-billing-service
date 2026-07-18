package br.com.oficina.billing.framework.messaging;

import br.com.oficina.billing.core.entities.ItemOrcamento;
import br.com.oficina.billing.core.entities.TipoItemOrcamento;
import br.com.oficina.billing.core.interfaces.gateway.FinanceiroSnapshotGateway;
import br.com.oficina.billing.core.interfaces.sender.OutboxEventSender;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface BillingEventStore extends FinanceiroSnapshotGateway, OutboxEventSender {
    boolean registrarEventoConsumido(DomainEventEnvelope envelope);

    boolean registrarEventoConsumido(UUID eventId);

    boolean eventoConsumido(UUID eventId);

    void registrarItem(UUID ordemServicoId, ItemOrcamento item);

    void registrarContato(UUID ordemServicoId, String clienteEmail);

    Optional<String> buscarContato(UUID ordemServicoId);

    void substituirTokensAprovacao(UUID ordemServicoId, UUID orcamentoId, String clienteEmail,
            List<ApprovalTokenRecord> tokens);

    Optional<ApprovalTokenGrant> buscarTokenAprovacao(String tokenHash);

    boolean consumirTokenAprovacao(String tokenHash, OffsetDateTime usadoEm);

    boolean liberarTokenAprovacao(String tokenHash, OffsetDateTime usadoEm);

    List<OutboxEventRecord> listarOutbox();

    List<OutboxEventRecord> publicarPendentes();

    List<OutboxEventRecord> listarPendentesParaPublicacao(int limit);

    default List<OutboxEventRecord> reivindicarPendentesParaPublicacao(
            int limit, String claimOwner, OffsetDateTime claimUntil) {
        return listarPendentesParaPublicacao(limit);
    }

    OutboxEventRecord marcarPublicado(UUID eventId);

    default OutboxEventRecord marcarPublicado(UUID eventId, String claimOwner) {
        return marcarPublicado(eventId);
    }

    OutboxEventRecord marcarFalhaPublicacao(UUID eventId, String lastError, OffsetDateTime nextAttemptAt, boolean failed);

    default OutboxEventRecord marcarFalhaPublicacao(
            UUID eventId, String lastError, OffsetDateTime nextAttemptAt, boolean failed, String claimOwner) {
        return marcarFalhaPublicacao(eventId, lastError, nextAttemptAt, failed);
    }

    default ItemOrcamento itemPeca(Map<String, Object> peca) {
        var pecaId = uuid(peca.get("pecaId"));
        return new ItemOrcamento(
                TipoItemOrcamento.PECA,
                pecaId,
                pecaId,
                texto(peca.get("nome"), "Peca sem nome"),
                decimal(peca.get("quantidade")),
                decimal(peca.get("valorUnitario")),
                decimal(peca.get("valorTotal")));
    }

    default ItemOrcamento itemServico(Map<String, Object> servico) {
        var servicoId = uuid(servico.get("servicoId"));
        return new ItemOrcamento(
                TipoItemOrcamento.SERVICO,
                servicoId,
                servicoId,
                texto(servico.get("nome"), "Servico sem nome"),
                decimal(servico.get("quantidade")),
                decimal(servico.get("valorUnitario")),
                decimal(servico.get("valorTotal")));
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("UUID obrigatorio no payload do evento.");
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Valor numerico obrigatorio no payload do evento.");
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static String texto(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
