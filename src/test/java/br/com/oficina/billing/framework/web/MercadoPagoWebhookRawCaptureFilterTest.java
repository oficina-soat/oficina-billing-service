package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class MercadoPagoWebhookRawCaptureFilterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T17:00:00Z"), ZoneOffset.UTC);

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void deveCapturarUmaRequisicaoCompletaNoLabEPreservarOBody() throws Exception {
        var capturePath = tempDir.resolve("request.json");
        var body = "{\"action\":\"order.processed\",\"data\":{\"id\":\"ORD01ABC\"}}";
        var context = context(body);
        var filter = new MercadoPagoWebhookRawCaptureFilter(
                true, "lab", capturePath, new ObjectMapper(), CLOCK);

        filter.filter(context);

        var captured = new ObjectMapper().readTree(Files.readString(capturePath));
        assertEquals("2026-07-21T17:00:00Z", captured.path("capturedAt").asText());
        assertEquals("lab", captured.path("environment").asText());
        assertEquals("POST", captured.path("method").asText());
        assertEquals(
                "https://lab.example/api/v1/integracoes/mercado-pago/webhooks?data.id=ORD01ABC&type=order",
                captured.path("requestUri").asText());
        assertEquals("ts=123,v1=hash-real", captured.path("headers").path("x-signature").get(0).asText());
        assertEquals(body, captured.path("body").asText());
        var restoredEntity = ArgumentCaptor.forClass(java.io.InputStream.class);
        verify(context).setEntityStream(restoredEntity.capture());
        assertEquals(body, new String(restoredEntity.getValue().readAllBytes(), StandardCharsets.UTF_8));

        var secondContext = context("{\"data\":{\"id\":\"OUTRA\"}}");
        filter.filter(secondContext);
        assertEquals(body, new ObjectMapper().readTree(Files.readString(capturePath)).path("body").asText());
    }

    @Test
    void deveIgnorarQuandoDesabilitadoOuForaDaRota() throws Exception {
        var disabledPath = tempDir.resolve("disabled.json");
        var disabled = new MercadoPagoWebhookRawCaptureFilter(
                false, "prod", disabledPath, new ObjectMapper(), CLOCK);
        disabled.filter(context("{}"));
        assertFalse(Files.exists(disabledPath));

        var otherPath = tempDir.resolve("other.json");
        var otherContext = context("{}");
        when(otherContext.getUriInfo().getPath()).thenReturn("api/v1/pagamentos");
        var enabled = new MercadoPagoWebhookRawCaptureFilter(
                true, "test", otherPath, new ObjectMapper(), CLOCK);
        enabled.filter(otherContext);
        assertFalse(Files.exists(otherPath));
    }

    @Test
    void deveRecusarAtivacaoForaDeLabOuTest() {
        assertThrows(
                IllegalStateException.class,
                () -> new MercadoPagoWebhookRawCaptureFilter(
                        true, "prod", tempDir.resolve("prod.json"), new ObjectMapper(), CLOCK));
        assertThrows(
                IllegalStateException.class,
                () -> new MercadoPagoWebhookRawCaptureFilter(
                        true, "local", tempDir.resolve("local.json"), new ObjectMapper(), CLOCK));
    }

    private ContainerRequestContext context(String body) {
        var context = mock(ContainerRequestContext.class);
        var uriInfo = mock(UriInfo.class);
        var headers = new MultivaluedHashMap<String, String>();
        headers.add("x-request-id", "request-real");
        headers.add("x-signature", "ts=123,v1=hash-real");
        headers.add("content-type", "application/json");
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(MercadoPagoWebhookRawCaptureFilter.WEBHOOK_PATH);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(
                "https://lab.example/api/v1/integracoes/mercado-pago/webhooks?data.id=ORD01ABC&type=order"));
        when(context.getMethod()).thenReturn("POST");
        when(context.getHeaders()).thenReturn(headers);
        when(context.hasEntity()).thenReturn(true);
        when(context.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return context;
    }
}
