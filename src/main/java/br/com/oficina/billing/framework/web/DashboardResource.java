package br.com.oficina.billing.framework.web;

import static br.com.oficina.billing.framework.web.ResourceUniAdapter.toUni;

import br.com.oficina.billing.core.usecases.dashboard.ConsultarDashboardFaturamentoUseCase;
import br.com.oficina.billing.core.usecases.dashboard.ConsultarDashboardFaturamentoUseCase.DashboardFaturamento;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/v1/dashboard/faturamento")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"administrativo", "recepcionista"})
@Blocking
public class DashboardResource {
    private final ConsultarDashboardFaturamentoUseCase useCase;

    @Inject
    public DashboardResource(ConsultarDashboardFaturamentoUseCase useCase) {
        this.useCase = useCase;
    }

    @GET
    public Uni<DashboardFaturamento> consultar() {
        return toUni(useCase::executar);
    }
}
