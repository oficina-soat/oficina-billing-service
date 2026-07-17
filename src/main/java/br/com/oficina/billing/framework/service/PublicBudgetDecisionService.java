package br.com.oficina.billing.framework.service;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.framework.messaging.ApprovalTokenGrant;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import br.com.oficina.billing.interfaces.controllers.OrcamentoController;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class PublicBudgetDecisionService {
    private final BillingEventStore store;
    private final OrcamentoController controller;

    public PublicBudgetDecisionService(BillingEventStore store, OrcamentoController controller) {
        this.store = store;
        this.controller = controller;
    }

    public CompletableFuture<Orcamento> consultar(UUID ordemServicoId, String rawToken, String action) {
        var grant = validar(ordemServicoId, rawToken, action);
        return controller.consultarOrcamento(grant.orcamentoId());
    }

    public CompletableFuture<Orcamento> decidir(UUID ordemServicoId, String rawToken, String action, String motivo) {
        var hash = sha256(rawToken);
        var grant = validar(ordemServicoId, rawToken, action);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!store.consumirTokenAprovacao(hash, now)) {
            throw new TokenIndisponivelException("Este link ja foi utilizado ou expirou.");
        }
        var request = new OrcamentoController.DecisaoOrcamentoRequest(motivo);
        return "APROVAR".equals(action)
                ? controller.aprovarOrcamento(grant.orcamentoId(), request)
                : controller.recusarOrcamento(grant.orcamentoId(), request);
    }

    private ApprovalTokenGrant validar(UUID ordemServicoId, String rawToken, String action) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new TokenIndisponivelException("Link invalido ou expirado.");
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var grant = store.buscarTokenAprovacao(sha256(rawToken))
                .filter(value -> value.ordemServicoId() == null || value.ordemServicoId().equals(ordemServicoId))
                .filter(value -> value.action().equals(action))
                .filter(value -> value.disponivelEm(now))
                .orElseThrow(() -> new TokenIndisponivelException("Link invalido, utilizado ou expirado."));
        return grant;
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        }
    }

    public static final class TokenIndisponivelException extends RuntimeException {
        public TokenIndisponivelException(String message) {
            super(message);
        }
    }
}
