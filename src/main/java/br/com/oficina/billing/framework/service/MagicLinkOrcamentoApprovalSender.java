package br.com.oficina.billing.framework.service;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.interfaces.sender.OrcamentoApprovalSender;
import br.com.oficina.billing.framework.messaging.ApprovalTokenRecord;
import br.com.oficina.billing.framework.messaging.BillingEventStore;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class MagicLinkOrcamentoApprovalSender implements OrcamentoApprovalSender {
    private static final int TOKEN_BYTES = 32;
    private static final long VALIDITY_HOURS = 24;

    private final BillingEventStore store;
    private final NotificacaoClient client;
    private final String publicBaseUrl;
    private final boolean enabled;
    private final SecureRandom secureRandom = new SecureRandom();

    public MagicLinkOrcamentoApprovalSender(
            BillingEventStore store,
            @RestClient NotificacaoClient client,
            @ConfigProperty(name = "oficina.approval.public-base-url") String publicBaseUrl,
            @ConfigProperty(name = "oficina.approval.notifications.enabled") boolean enabled) {
        this.store = store;
        this.client = client;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        this.enabled = enabled;
    }

    @Override
    public CompletableFuture<Void> enviar(Orcamento orcamento) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            var email = store.buscarContato(orcamento.ordemServicoId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Contato do cliente nao projetado para a ordem de servico."));
            var now = OffsetDateTime.now(ZoneOffset.UTC);
            var rawTokens = new LinkedHashMap<String, String>();
            var records = new ArrayList<ApprovalTokenRecord>();
            for (var action : new String[] {"ACOMPANHAR", "APROVAR", "RECUSAR"}) {
                var rawToken = generateToken();
                rawTokens.put(action, rawToken);
                records.add(new ApprovalTokenRecord(
                        java.util.UUID.randomUUID(), sha256(rawToken), action, now, now.plusHours(VALIDITY_HOURS)));
            }
            store.substituirTokensAprovacao(
                    orcamento.ordemServicoId(), orcamento.orcamentoId(), email, records);
            client.enviarEmail(new NotificacaoEmailRequest(
                    email,
                    "Orcamento da ordem de servico",
                    emailBody(orcamento, rawTokens)));
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private String emailBody(Orcamento orcamento, LinkedHashMap<String, String> tokens) {
        return """
                O orçamento da sua ordem de serviço está disponível.

                Valor total: R$ %s

                Acompanhar: %s
                Aprovar: %s
                Recusar: %s

                Os links são individuais, expiram em 24 horas e só podem ser usados uma vez.
                """.formatted(
                orcamento.valorTotal().toPlainString(),
                link(orcamento, "acompanhar-link", tokens.get("ACOMPANHAR")),
                link(orcamento, "aprovar-link", tokens.get("APROVAR")),
                link(orcamento, "recusar-link", tokens.get("RECUSAR")));
    }

    private String link(Orcamento orcamento, String route, String token) {
        return "%s/api/v1/ordens-servico/%s/%s?actionToken=%s".formatted(
                publicBaseUrl, orcamento.ordemServicoId(), route, token);
    }

    private String generateToken() {
        var bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
