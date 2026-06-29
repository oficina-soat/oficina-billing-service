package br.com.oficina.billing.interfaces.controllers;

import br.com.oficina.billing.core.entities.MetodoPagamento;
import br.com.oficina.billing.core.entities.Pagamento;
import br.com.oficina.billing.core.exceptions.BusinessException;
import br.com.oficina.billing.core.usecases.PagamentoService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PagamentoResource {
    private final PagamentoService pagamentoService;

    public PagamentoResource(PagamentoService pagamentoService) {
        this.pagamentoService = pagamentoService;
    }

    @POST
    @Path("/pagamentos")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Response registrarPagamento(PagamentoCreateRequest request) {
        validar(request);
        var pagamento = pagamentoService.registrar(
                request.ordemServicoId(),
                request.orcamentoId(),
                request.valor(),
                request.metodo());
        var location = UriBuilder.fromPath("/api/v1/pagamentos/{pagamentoId}")
                .build(pagamento.pagamentoId());
        return Response.created(location).entity(pagamento).build();
    }

    @GET
    @Path("/pagamentos/{pagamentoId}")
    public Pagamento consultarPagamento(@PathParam("pagamentoId") UUID pagamentoId) {
        return pagamentoService.consultar(pagamentoId);
    }

    @GET
    @Path("/ordens-servico/{ordemServicoId}/pagamentos")
    public List<Pagamento> consultarPagamentosDaOrdemServico(@PathParam("ordemServicoId") UUID ordemServicoId) {
        return pagamentoService.consultarPorOrdemServico(ordemServicoId);
    }

    @POST
    @Path("/pagamentos/{pagamentoId}/confirmacao")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Pagamento confirmarPagamento(@PathParam("pagamentoId") UUID pagamentoId, ConfirmacaoPagamentoRequest request) {
        return pagamentoService.confirmar(
                pagamentoId,
                request == null ? null : request.provedor(),
                request == null ? null : request.transacaoExternaId());
    }

    @POST
    @Path("/pagamentos/{pagamentoId}/recusa")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Pagamento recusarPagamento(@PathParam("pagamentoId") UUID pagamentoId, RecusaPagamentoRequest request) {
        return pagamentoService.recusar(pagamentoId, request == null ? null : request.provedor());
    }

    @POST
    @Path("/pagamentos/{pagamentoId}/cancelamento")
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Pagamento cancelarPagamento(@PathParam("pagamentoId") UUID pagamentoId, CancelamentoRequest request) {
        return pagamentoService.cancelar(pagamentoId);
    }

    private void validar(PagamentoCreateRequest request) {
        if (request == null
                || request.ordemServicoId() == null
                || request.orcamentoId() == null
                || request.valor() == null
                || request.metodo() == null) {
            throw new BusinessException("VALIDATION_ERROR", "Campos ordemServicoId, orcamentoId, valor e metodo sao obrigatorios.");
        }
        if (request.valor().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("VALIDATION_ERROR", "Valor do pagamento nao pode ser negativo.");
        }
    }

    public record PagamentoCreateRequest(
            UUID ordemServicoId,
            UUID orcamentoId,
            BigDecimal valor,
            MetodoPagamento metodo) {
    }

    public record ConfirmacaoPagamentoRequest(String provedor, String transacaoExternaId) {
    }

    public record RecusaPagamentoRequest(String provedor, String motivo) {
    }

    public record CancelamentoRequest(String motivo) {
    }
}
