package br.com.oficina.billing.framework.web;

import static br.com.oficina.billing.framework.web.ResourceUniAdapter.toUni;

import br.com.oficina.billing.interfaces.controllers.OrcamentoController;
import br.com.oficina.billing.interfaces.presenters.OrcamentoPresenterAdapter;
import br.com.oficina.billing.interfaces.presenters.view_model.OrcamentoViewModel;
import io.smallrye.common.annotation.Blocking;
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
@Blocking
public class OrcamentoResource {
    private final OrcamentoController orcamentoController;
    private final OrcamentoPresenterAdapter orcamentoPresenter;

    @Inject
    public OrcamentoResource(OrcamentoController orcamentoController, OrcamentoPresenterAdapter orcamentoPresenter) {
        this.orcamentoController = orcamentoController;
        this.orcamentoPresenter = orcamentoPresenter;
    }

    @POST
    @Path("/orcamentos")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<Response> gerarOrcamento(OrcamentoController.GerarOrcamentoRequest request) {
        return toUni(() -> orcamentoController.gerarOrcamento(request)
                .thenApply(orcamento -> {
                    orcamentoPresenter.present(orcamento);
                    var viewModel = orcamentoPresenter.viewModel();
                    var location = UriBuilder.fromPath("/api/v1/orcamentos/{orcamentoId}")
                            .build(viewModel.orcamentoId());
                    return Response.created(location).entity(viewModel).build();
                }));
    }

    @GET
    @Path("/orcamentos/{orcamentoId}")
    public Uni<OrcamentoViewModel> consultarOrcamento(@PathParam("orcamentoId") UUID orcamentoId) {
        return toUni(() -> orcamentoController.consultarOrcamento(orcamentoId)
                .thenApply(orcamento -> {
                    orcamentoPresenter.present(orcamento);
                    return orcamentoPresenter.viewModel();
                }));
    }

    @GET
    @Path("/ordens-servico/{ordemServicoId}/orcamentos")
    public Uni<List<OrcamentoViewModel>> consultarOrcamentosDaOrdemServico(
            @PathParam("ordemServicoId") UUID ordemServicoId) {
        return toUni(() -> orcamentoController.consultarOrcamentosDaOrdemServico(ordemServicoId)
                .thenApply(orcamentos -> {
                    orcamentoPresenter.present(orcamentos);
                    return orcamentoPresenter.viewModels();
                }));
    }

    @POST
    @Path("/orcamentos/{orcamentoId}/aprovacao")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<OrcamentoViewModel> aprovarOrcamento(
            @PathParam("orcamentoId") UUID orcamentoId,
            OrcamentoController.DecisaoOrcamentoRequest request) {
        return toUni(() -> orcamentoController.aprovarOrcamento(orcamentoId, request)
                .thenApply(orcamento -> {
                    orcamentoPresenter.present(orcamento);
                    return orcamentoPresenter.viewModel();
                }));
    }

    @POST
    @Path("/orcamentos/{orcamentoId}/recusa")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<OrcamentoViewModel> recusarOrcamento(
            @PathParam("orcamentoId") UUID orcamentoId,
            OrcamentoController.DecisaoOrcamentoRequest request) {
        return toUni(() -> orcamentoController.recusarOrcamento(orcamentoId, request)
                .thenApply(orcamento -> {
                    orcamentoPresenter.present(orcamento);
                    return orcamentoPresenter.viewModel();
                }));
    }
}
