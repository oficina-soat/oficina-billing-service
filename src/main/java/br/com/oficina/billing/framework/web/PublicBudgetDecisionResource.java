package br.com.oficina.billing.framework.web;

import static br.com.oficina.billing.framework.web.ResourceUniAdapter.toUni;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.framework.service.PublicBudgetDecisionService;
import br.com.oficina.billing.framework.service.PublicBudgetDecisionService.TokenIndisponivelException;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@Path("/api/v1")
@Produces(MediaType.TEXT_HTML)
@Blocking
public class PublicBudgetDecisionResource {
    private final PublicBudgetDecisionService service;

    public PublicBudgetDecisionResource(PublicBudgetDecisionService service) {
        this.service = service;
    }

    @GET
    @Path("/ordens-servico/{ordemServicoId}/acompanhar-link")
    public Uni<Response> acompanhar(
            @PathParam("ordemServicoId") UUID ordemServicoId,
            @QueryParam("actionToken") String actionToken) {
        return pagina(() -> service.consultar(ordemServicoId, actionToken, "ACOMPANHAR"), null, actionToken);
    }

    @GET
    @Path("/ordens-servico/{ordemServicoId}/aprovar-link")
    public Uni<Response> abrirAprovacao(
            @PathParam("ordemServicoId") UUID ordemServicoId,
            @QueryParam("actionToken") String actionToken) {
        return pagina(() -> service.consultar(ordemServicoId, actionToken, "APROVAR"), "APROVAR", actionToken);
    }

    @POST
    @Path("/ordens-servico/{ordemServicoId}/aprovar-link")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Uni<Response> aprovar(
            @PathParam("ordemServicoId") UUID ordemServicoId,
            @FormParam("actionToken") String actionToken,
            @FormParam("motivo") String motivo) {
        return resultado(() -> service.decidir(ordemServicoId, actionToken, "APROVAR", motivo), "Orçamento aprovado");
    }

    @GET
    @Path("/ordens-servico/{ordemServicoId}/recusar-link")
    public Uni<Response> abrirRecusa(
            @PathParam("ordemServicoId") UUID ordemServicoId,
            @QueryParam("actionToken") String actionToken) {
        return pagina(() -> service.consultar(ordemServicoId, actionToken, "RECUSAR"), "RECUSAR", actionToken);
    }

    @POST
    @Path("/ordens-servico/{ordemServicoId}/recusar-link")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Uni<Response> recusar(
            @PathParam("ordemServicoId") UUID ordemServicoId,
            @FormParam("actionToken") String actionToken,
            @FormParam("motivo") String motivo) {
        return resultado(() -> service.decidir(ordemServicoId, actionToken, "RECUSAR", motivo), "Orçamento recusado");
    }

    private Uni<Response> pagina(Action action, String decision, String token) {
        return toUni(action::execute)
                .map(orcamento -> Response.ok(html(orcamento, decision, token)).build())
                .onFailure(TokenIndisponivelException.class)
                .recoverWithItem(error(401, "Link indisponível", "Solicite um novo link à oficina."));
    }

    private Uni<Response> resultado(Action action, String title) {
        return toUni(action::execute)
                .map(orcamento -> Response.ok(document(title,
                        "A decisão foi registrada com sucesso para a ordem de serviço "
                                + escape(orcamento.ordemServicoId().toString()) + ".", "")).build())
                .onFailure(TokenIndisponivelException.class)
                .recoverWithItem(error(409, "Decisão não registrada", "O link já foi utilizado ou expirou."));
    }

    private Response error(int status, String title, String message) {
        return Response.status(status).entity(document(title, message, "")).build();
    }

    private String html(Orcamento orcamento, String decision, String token) {
        var items = new StringBuilder("<ul>");
        orcamento.itens().forEach(item -> items.append("<li>")
                .append(escape(item.nome())).append(" — ")
                .append(escape(item.quantidade().toPlainString())).append(" × R$ ")
                .append(escape(item.valorUnitario().toPlainString())).append("</li>"));
        items.append("</ul>");
        var form = decision == null ? "" : """
                <form method="post">
                  <input type="hidden" name="actionToken" value="%s">
                  <label>Observação (opcional)<textarea name="motivo" maxlength="500"></textarea></label>
                  <button type="submit">%s orçamento</button>
                </form>
                """.formatted(escape(token), "APROVAR".equals(decision) ? "Aprovar" : "Recusar");
        return document("Orçamento da ordem de serviço",
                "<p>Status: <strong>" + escape(orcamento.status().name()) + "</strong></p>"
                        + items + "<p class=\"total\">Total: R$ "
                        + escape(orcamento.valorTotal().toPlainString()) + "</p>", form);
    }

    private String document(String title, String content, String action) {
        return """
                <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s | Oficina SOAT</title><style>
                body{font-family:system-ui,sans-serif;background:#f5f7fa;color:#17202a;margin:0;padding:2rem}
                main{max-width:42rem;margin:auto;background:white;padding:2rem;border-radius:.75rem;box-shadow:0 2px 12px #0002}
                h1{color:#174a7e}label{display:grid;gap:.5rem}textarea{min-height:6rem;padding:.75rem}
                button{margin-top:1rem;padding:.8rem 1.2rem;background:#174a7e;color:white;border:0;border-radius:.35rem;font-weight:700}
                .total{font-size:1.25rem;font-weight:700}</style></head><body><main>
                <h1>%s</h1>%s%s</main></body></html>
                """.formatted(escape(title), escape(title), content, action);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @FunctionalInterface
    private interface Action {
        java.util.concurrent.CompletableFuture<Orcamento> execute();
    }
}
