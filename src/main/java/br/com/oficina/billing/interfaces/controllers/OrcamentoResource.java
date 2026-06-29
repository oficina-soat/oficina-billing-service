package br.com.oficina.billing.interfaces.controllers;

import br.com.oficina.billing.core.entities.Orcamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.usecases.OrcamentoService;
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

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OrcamentoResource {
    private final OrcamentoService orcamentoService;

    public OrcamentoResource(OrcamentoService orcamentoService) {
        this.orcamentoService = orcamentoService;
    }

    @POST
    @Path("/orcamentos")
    public Response gerarOrcamento(GerarOrcamentoRequest request) {
        validar(request);
        var orcamento = orcamentoService.gerar(request.ordemServicoId());
        var location = UriBuilder.fromPath("/api/v1/orcamentos/{orcamentoId}")
                .build(orcamento.orcamentoId());
        return Response.created(location).entity(orcamento).build();
    }

    @GET
    @Path("/orcamentos/{orcamentoId}")
    public Orcamento consultarOrcamento(@PathParam("orcamentoId") UUID orcamentoId) {
        return orcamentoService.consultar(orcamentoId);
    }

    @GET
    @Path("/ordens-servico/{ordemServicoId}/orcamentos")
    public List<Orcamento> consultarOrcamentosDaOrdemServico(@PathParam("ordemServicoId") UUID ordemServicoId) {
        return orcamentoService.consultarPorOrdemServico(ordemServicoId);
    }

    @POST
    @Path("/orcamentos/{orcamentoId}/aprovacao")
    public Orcamento aprovarOrcamento(@PathParam("orcamentoId") UUID orcamentoId, DecisaoOrcamentoRequest request) {
        return orcamentoService.aprovar(orcamentoId);
    }

    @POST
    @Path("/orcamentos/{orcamentoId}/recusa")
    public Orcamento recusarOrcamento(@PathParam("orcamentoId") UUID orcamentoId, DecisaoOrcamentoRequest request) {
        return orcamentoService.recusar(orcamentoId);
    }

    private void validar(GerarOrcamentoRequest request) {
        if (request == null || request.ordemServicoId() == null) {
            throw new BusinessException("VALIDATION_ERROR", "Campo ordemServicoId e obrigatorio.");
        }
    }

    public record GerarOrcamentoRequest(UUID ordemServicoId) {
    }

    public record DecisaoOrcamentoRequest(String motivo) {
    }
}
