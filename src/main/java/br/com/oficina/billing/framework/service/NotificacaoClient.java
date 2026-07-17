package br.com.oficina.billing.framework.service;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "notification-api")
@Path("/notificacoes")
@Consumes(MediaType.APPLICATION_JSON)
public interface NotificacaoClient {
    @POST
    @Path("/email")
    void enviarEmail(NotificacaoEmailRequest request);
}
