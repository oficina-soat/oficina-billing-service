package br.com.oficina.billing.framework.web;

import static br.com.oficina.billing.framework.web.ResourceUniAdapter.toUni;

import br.com.oficina.billing.interfaces.controllers.PagamentoController;
import br.com.oficina.billing.interfaces.presenters.PagamentoPresenterAdapter;
import br.com.oficina.billing.interfaces.presenters.view_model.PagamentoViewModel;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PagamentoResource {
    @Inject
    PagamentoController pagamentoController;

    @Inject
    PagamentoPresenterAdapter pagamentoPresenter;

    @POST
    @Path("/pagamentos")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<Response> registrarPagamento(PagamentoController.PagamentoCreateRequest request) {
        return toUni(() -> pagamentoController.registrarPagamento(request)
                .thenApply(pagamento -> {
                    pagamentoPresenter.present(pagamento);
                    var viewModel = pagamentoPresenter.viewModel();
                    var location = UriBuilder.fromPath("/api/v1/pagamentos/{pagamentoId}")
                            .build(viewModel.pagamentoId());
                    return Response.created(location).entity(viewModel).build();
                }));
    }

    @GET
    @Path("/pagamentos/{pagamentoId}")
    public Uni<PagamentoViewModel> consultarPagamento(@PathParam("pagamentoId") UUID pagamentoId) {
        return toUni(() -> pagamentoController.consultarPagamento(pagamentoId)
                .thenApply(pagamento -> {
                    pagamentoPresenter.present(pagamento);
                    return pagamentoPresenter.viewModel();
                }));
    }

    @GET
    @Path("/ordens-servico/{ordemServicoId}/pagamentos")
    public Uni<List<PagamentoViewModel>> consultarPagamentosDaOrdemServico(
            @PathParam("ordemServicoId") UUID ordemServicoId) {
        return toUni(() -> pagamentoController.consultarPagamentosDaOrdemServico(ordemServicoId)
                .thenApply(pagamentos -> {
                    pagamentoPresenter.present(pagamentos);
                    return pagamentoPresenter.viewModels();
                }));
    }

    @POST
    @Path("/pagamentos/{pagamentoId}/confirmacao")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<PagamentoViewModel> confirmarPagamento(
            @PathParam("pagamentoId") UUID pagamentoId,
            PagamentoController.ConfirmacaoPagamentoRequest request) {
        return toUni(() -> pagamentoController.confirmarPagamento(pagamentoId, request)
                .thenApply(pagamento -> {
                    pagamentoPresenter.present(pagamento);
                    return pagamentoPresenter.viewModel();
                }));
    }

    @POST
    @Path("/pagamentos/{pagamentoId}/recusa")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<PagamentoViewModel> recusarPagamento(
            @PathParam("pagamentoId") UUID pagamentoId,
            PagamentoController.RecusaPagamentoRequest request) {
        return toUni(() -> pagamentoController.recusarPagamento(pagamentoId, request)
                .thenApply(pagamento -> {
                    pagamentoPresenter.present(pagamento);
                    return pagamentoPresenter.viewModel();
                }));
    }

    @POST
    @Path("/pagamentos/{pagamentoId}/cancelamento")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<PagamentoViewModel> cancelarPagamento(
            @PathParam("pagamentoId") UUID pagamentoId,
            PagamentoController.CancelamentoRequest request) {
        return toUni(() -> pagamentoController.cancelarPagamento(pagamentoId, request)
                .thenApply(pagamento -> {
                    pagamentoPresenter.present(pagamento);
                    return pagamentoPresenter.viewModel();
                }));
    }
}
