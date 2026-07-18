package br.com.oficina.billing.framework.payments;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "mercado-pago-api")
@Path("/v1/payments")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface MercadoPagoQueryClient {
    @GET
    @Path("/{paymentId}")
    MercadoPagoPaymentResponse getPayment(
            @HeaderParam("Authorization") String authorization,
            @PathParam("paymentId") String paymentId);
}
