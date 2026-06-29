package br.com.oficina.billing.interfaces.controllers;

import jakarta.annotation.security.PermitAll;
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
    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;

    @ConfigProperty(name = "oficina.observability.deployment-environment")
    String environment;

    @GET
    @PermitAll
    public StatusResponse status() {
        return new StatusResponse(applicationName, environment, "UP");
    }

    @POST
    @PermitAll
    @Parameter(name = "X-Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Chave de idempotência da operação mutável.")
    public StatusResponse mutatingStatusProbe() {
        return status();
    }

    public record StatusResponse(String service, String environment, String status) {
    }
}
