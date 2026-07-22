package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MercadoPagoWebhookRawDumpFilterTest {
    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void deveFazerDumpSemAlterarBodyNemProcessamento() throws Exception {
        var path = tempDir.resolve("request.log");
        var body = "{\"data\":{\"id\":\"ORDABC\"}}";
        var context = context(body);
        var filter = new MercadoPagoWebhookRawDumpFilter(true, "test", path);

        filter.filter(context);

        assertEquals(
                "POST https://test.example/api/v1/integracoes/mercado-pago/webhooks?data.id=ORDABC&type=order\n"
                        + "x-request-id: request-real\n"
                        + "x-signature: ts=123,v1=hash-real\n\n"
                        + body,
                Files.readString(path));
        var restored = ArgumentCaptor.forClass(java.io.InputStream.class);
        verify(context).setEntityStream(restored.capture());
        assertEquals(body, new String(restored.getValue().readAllBytes(), StandardCharsets.UTF_8));

        filter.filter(context("outro"));
        assertEquals(body, Files.readString(path).substring(Files.readString(path).indexOf("{\"data")));
    }

    @Test
    void deveIgnorarQuandoDesabilitadoOuForaDaRota() throws Exception {
        var disabled = tempDir.resolve("disabled.log");
        new MercadoPagoWebhookRawDumpFilter(false, "prod", disabled).filter(context("{}"));
        assertFalse(Files.exists(disabled));

        var other = tempDir.resolve("other.log");
        var context = context("{}");
        when(context.getUriInfo().getPath()).thenReturn("api/v1/pagamentos");
        new MercadoPagoWebhookRawDumpFilter(true, "test", other).filter(context);
        assertFalse(Files.exists(other));
    }

    @Test
    void deveRecusarAtivacaoForaDeLabOuTest() {
        assertThrows(
                IllegalStateException.class,
                () -> new MercadoPagoWebhookRawDumpFilter(true, "prod", tempDir.resolve("prod.log")));
        assertThrows(
                IllegalStateException.class,
                () -> new MercadoPagoWebhookRawDumpFilter(true, "local", tempDir.resolve("local.log")));
    }

    private ContainerRequestContext context(String body) {
        var context = mock(ContainerRequestContext.class);
        var uriInfo = mock(UriInfo.class);
        var headers = new MultivaluedHashMap<String, String>();
        headers.add("x-request-id", "request-real");
        headers.add("x-signature", "ts=123,v1=hash-real");
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(MercadoPagoWebhookRawDumpFilter.WEBHOOK_PATH);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(
                "https://test.example/api/v1/integracoes/mercado-pago/webhooks?data.id=ORDABC&type=order"));
        when(context.getMethod()).thenReturn("POST");
        when(context.getHeaders()).thenReturn(headers);
        when(context.hasEntity()).thenReturn(true);
        when(context.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return context;
    }
}
