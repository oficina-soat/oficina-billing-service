package br.com.oficina.billing.framework.web;

import static br.com.oficina.billing.framework.web.ResourceUniAdapter.toUni;

import br.com.oficina.billing.interfaces.controllers.StatusController;
import br.com.oficina.billing.interfaces.presenters.view_model.StatusViewModel;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

@Path("/api/v1/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {
    private final StatusController statusController;
    private final String applicationName;
    private final String environment;

    @Inject
    public StatusResource(
            StatusController statusController,
            @ConfigProperty(name = "quarkus.application.name") String applicationName,
            @ConfigProperty(name = "oficina.observability.deployment-environment") String environment) {
        this.statusController = statusController;
        this.applicationName = applicationName;
        this.environment = environment;
    }

    @GET
    @PermitAll
    public Uni<StatusViewModel> status() {
        return toUni(() -> statusController.status(new StatusController.StatusRequest(applicationName, environment, "UP")));
    }

    @POST
    @PermitAll
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public Uni<StatusViewModel> mutatingStatusProbe() {
        return status();
    }
}
