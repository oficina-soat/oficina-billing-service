package br.com.oficina.billing.framework.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

class AwsDomainMessagingClientTest {
    @Test
    void deveUsarCredenciaisLocaisQuandoEndpointOverrideForInformado() {
        var provider = AwsDomainMessagingClient.credentialsProvider("", "", "", "http://localhost:4566");

        var credentials = provider.resolveCredentials();

        assertEquals("local", credentials.accessKeyId());
        assertEquals("local", credentials.secretAccessKey());
    }

    @Test
    void deveUsarCredenciaisBasicasQuandoParEstaticoForInformado() {
        var provider = AwsDomainMessagingClient.credentialsProvider("access", "secret", "", "");

        var credentials = provider.resolveCredentials();

        assertEquals("access", credentials.accessKeyId());
        assertEquals("secret", credentials.secretAccessKey());
    }

    @Test
    void devePreservarSessionTokenDeCredencialTemporaria() {
        var provider = AwsDomainMessagingClient.credentialsProvider("access", "secret", "session", "");

        var credentials = assertInstanceOf(AwsSessionCredentials.class, provider.resolveCredentials());

        assertEquals("session", credentials.sessionToken());
    }

    @Test
    void deveUsarCadeiaPadraoSemCredencialEstatica() {
        var provider = AwsDomainMessagingClient.credentialsProvider("", "", "", "");

        assertInstanceOf(DefaultCredentialsProvider.class, provider);
    }

    @Test
    void deveRejeitarCredenciaisEstaticasIncompletas() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AwsDomainMessagingClient.credentialsProvider("access", "", "", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> AwsDomainMessagingClient.credentialsProvider("", "", "session", ""));
    }
}
