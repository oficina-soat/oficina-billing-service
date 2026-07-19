package br.com.oficina.billing.framework.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

class RuntimeDependenciesValidatorTest {
    @Test
    void deveIgnorarValidacaoDeDependenciasEmRuntimeLocal() {
        var probes = new AtomicInteger();
        var validator = new RuntimeDependenciesValidator(
                config(Map.of("oficina.observability.deployment-environment", "local")),
                probes::incrementAndGet,
                probes::incrementAndGet);

        validator.validate("test");

        assertEquals(0, probes.get());
        assertFalse(RuntimeDependenciesValidator.isProtectedRuntime(null, null));
    }

    @Test
    void deveProtegerProfileProdEDeploymentLab() {
        assertTrue(RuntimeDependenciesValidator.isProtectedRuntime(" PROD ", "local"));
        assertTrue(RuntimeDependenciesValidator.isProtectedRuntime(" LAB ", "local"));
        assertTrue(RuntimeDependenciesValidator.isProtectedRuntime("test", " LAB "));
        assertFalse(RuntimeDependenciesValidator.isProtectedRuntime("dev", "local"));
    }

    @Test
    void deveExecutarProbesQuandoConfiguracaoProtegidaForValida() {
        var databaseProbe = new AtomicBoolean();
        var messagingProbe = new AtomicBoolean();
        var validator = new RuntimeDependenciesValidator(
                config(validProperties()),
                () -> databaseProbe.set(true),
                () -> messagingProbe.set(true));

        validator.validate("prod");

        assertTrue(databaseProbe.get());
        assertTrue(messagingProbe.get());
    }

    @Test
    void deveExecutarProbesQuandoDeploymentForLab() {
        var properties = validProperties();
        properties.put("oficina.observability.deployment-environment", "lab");
        var probes = new AtomicInteger();
        var validator = new RuntimeDependenciesValidator(
                config(properties),
                probes::incrementAndGet,
                probes::incrementAndGet);

        validator.validate("test");

        assertEquals(2, probes.get());
    }

    @Test
    void deveRejeitarFallbacksEConfiguracoesObrigatoriasInvalidas() {
        var properties = validProperties();
        properties.put("oficina.persistence.kind", "memory");
        properties.put("quarkus.datasource.active", "false");
        properties.put("quarkus.flyway.active", "false");
        properties.put("quarkus.flyway.migrate-at-start", "false");
        properties.put("quarkus.datasource.username", "DB_USERNAME_PLACEHOLDER");
        properties.put("quarkus.datasource.password", "");
        properties.put("quarkus.datasource.jdbc.url", "");
        properties.put("quarkus.datasource.reactive.url", "");
        properties.put("mp.jwt.verify.issuer", "OFICINA_AUTH_ISSUER_PLACEHOLDER");
        properties.put("mp.jwt.verify.audiences", "oficina-app");
        properties.put("mp.jwt.verify.publickey.location", "JWKS_PLACEHOLDER");
        properties.put("oficina.messaging.enabled", "false");
        properties.put("oficina.messaging.publisher.enabled", "false");
        properties.put("oficina.messaging.consumer.enabled", "false");
        properties.put("oficina.messaging.worker.enabled", "false");
        properties.put("AWS_REGION", "");
        properties.put("oficina.messaging.endpoint-override", "http://localhost:4566");
        properties.put("oficina.messaging.aws-access-key-id", "ACCESS_PLACEHOLDER");
        properties.put("oficina.messaging.aws-secret-access-key", "SECRET_PLACEHOLDER");
        properties.put("oficina.mercado-pago.enabled", "true");
        properties.put("oficina.mercado-pago.access-token", "");
        properties.put("oficina.mercado-pago.webhook-secret", "");
        properties.put("oficina.mercado-pago.api-mode", "unknown");
        properties.put("oficina.mercado-pago.payer-email", "PAYER_PLACEHOLDER");
        properties.put("oficina.mercado-pago.payer-first-name", "APRO");
        properties.put("quarkus.rest-client.mercado-pago-api.url", "");
        var probes = new AtomicInteger();
        var validator = new RuntimeDependenciesValidator(
                config(properties),
                probes::incrementAndGet,
                probes::incrementAndGet);

        var exception = assertThrows(IllegalStateException.class, () -> validator.validate("prod"));

        assertEquals(0, probes.get());
        assertTrue(exception.getMessage().contains("oficina.persistence.kind deve ser postgresql"));
        assertTrue(exception.getMessage().contains("quarkus.datasource.active"));
        assertTrue(exception.getMessage().contains("quarkus.flyway.active"));
        assertTrue(exception.getMessage().contains("quarkus.flyway.migrate-at-start"));
        assertTrue(exception.getMessage().contains("DB_USERNAME"));
        assertTrue(exception.getMessage().contains("DB_PASSWORD"));
        assertTrue(exception.getMessage().contains("JDBC_DATABASE_URL"));
        assertTrue(exception.getMessage().contains("REACTIVE_DATABASE_URL"));
        assertTrue(exception.getMessage().contains("OFICINA_AUTH_ISSUER"));
        assertTrue(exception.getMessage().contains("OFICINA_AUTH_AUDIENCE"));
        assertTrue(exception.getMessage().contains("MP_JWT_VERIFY_PUBLICKEY_LOCATION"));
        assertTrue(exception.getMessage().contains("OFICINA_MESSAGING_ENABLED"));
        assertTrue(exception.getMessage().contains("publisher SNS"));
        assertTrue(exception.getMessage().contains("consumer SQS"));
        assertTrue(exception.getMessage().contains("worker de mensageria"));
        assertTrue(exception.getMessage().contains("AWS_REGION"));
        assertTrue(exception.getMessage().contains("OFICINA_MESSAGING_ENDPOINT_OVERRIDE"));
        assertTrue(exception.getMessage().contains("credenciais AWS estaticas nao podem conter placeholder"));
        assertTrue(exception.getMessage().contains("OFICINA_MERCADO_PAGO_ACCESS_TOKEN"));
        assertTrue(exception.getMessage().contains("OFICINA_MERCADO_PAGO_WEBHOOK_SECRET"));
        assertTrue(exception.getMessage().contains("OFICINA_MERCADO_PAGO_API_MODE"));
        assertTrue(exception.getMessage().contains("OFICINA_MERCADO_PAGO_PAYER_EMAIL"));
        assertTrue(exception.getMessage().contains("APRO e permitido apenas em lab ou test"));
        assertTrue(exception.getMessage().contains("usuario oficial no cenario APRO"));
        assertTrue(exception.getMessage().contains("OFICINA_MERCADO_PAGO_API_URL"));
    }

    @Test
    void deveExigirCredenciaisAwsEstaticasCompletas() {
        var properties = validProperties();
        properties.put("oficina.messaging.aws-session-token", "session-token");
        var validator = new RuntimeDependenciesValidator(config(properties), () -> {}, () -> {});

        var exception = assertThrows(IllegalStateException.class, () -> validator.validate("prod"));

        assertTrue(exception.getMessage().contains("credenciais AWS estaticas devem informar"));
    }

    @Test
    void devePermitirMercadoPagoDesabilitadoSemToken() {
        var properties = validProperties();
        properties.remove("oficina.mercado-pago.access-token");
        var probes = new AtomicInteger();
        var validator = new RuntimeDependenciesValidator(
                config(properties),
                probes::incrementAndGet,
                probes::incrementAndGet);

        validator.validate("prod");

        assertEquals(2, probes.get());
    }

    @Test
    void deveEncapsularFalhaDoPostgresqlSemExecutarProbeDeMensageria() {
        var messagingProbe = new AtomicBoolean();
        var databaseFailure = new IllegalStateException("database down");
        var validator = new RuntimeDependenciesValidator(
                config(validProperties()),
                () -> {
                    throw databaseFailure;
                },
                () -> messagingProbe.set(true));

        var exception = assertThrows(IllegalStateException.class, () -> validator.validate("prod"));

        assertTrue(exception.getMessage().contains("PostgreSQL"));
        assertEquals(databaseFailure, exception.getCause());
        assertFalse(messagingProbe.get());
    }

    @Test
    void deveEncapsularFalhaDeMensageriaAposValidarPostgresql() {
        var databaseProbe = new AtomicBoolean();
        var messagingFailure = new IllegalStateException("messaging down");
        var validator = new RuntimeDependenciesValidator(
                config(validProperties()),
                () -> databaseProbe.set(true),
                () -> {
                    throw messagingFailure;
                });

        var exception = assertThrows(IllegalStateException.class, () -> validator.validate("prod"));

        assertTrue(databaseProbe.get());
        assertTrue(exception.getMessage().contains("SNS/SQS"));
        assertEquals(messagingFailure, exception.getCause());
    }

    private static Map<String, String> validProperties() {
        var properties = new HashMap<String, String>();
        properties.put("oficina.observability.deployment-environment", "local");
        properties.put("oficina.persistence.kind", "postgresql");
        properties.put("quarkus.datasource.active", "true");
        properties.put("quarkus.flyway.active", "true");
        properties.put("quarkus.flyway.migrate-at-start", "true");
        properties.put("quarkus.datasource.username", "oficina_billing_user");
        properties.put("quarkus.datasource.password", "secret");
        properties.put("quarkus.datasource.jdbc.url", "jdbc:postgresql://database:5432/oficina_billing");
        properties.put("quarkus.datasource.reactive.url", "postgresql://database:5432/oficina_billing");
        properties.put("mp.jwt.verify.issuer", "oficina-api");
        properties.put("mp.jwt.verify.audiences", "oficina-billing-service");
        properties.put("mp.jwt.verify.publickey.location", "https://auth.example/.well-known/jwks.json");
        properties.put("oficina.messaging.enabled", "true");
        properties.put("oficina.messaging.publisher.enabled", "true");
        properties.put("oficina.messaging.consumer.enabled", "true");
        properties.put("oficina.messaging.worker.enabled", "true");
        properties.put("AWS_REGION", "us-east-1");
        properties.put("oficina.mercado-pago.enabled", "false");
        properties.put("oficina.mercado-pago.api-mode", "orders");
        properties.put("oficina.mercado-pago.payer-email", "cliente@oficina.com");
        properties.put("quarkus.rest-client.mercado-pago-api.url", "https://api.mercadopago.com");
        return properties;
    }

    private static Config config(Map<String, String> properties) {
        return ConfigProviderResolver.instance()
                .getBuilder()
                .withSources(new MapConfigSource(properties))
                .build();
    }

    private record MapConfigSource(Map<String, String> properties) implements ConfigSource {
        private MapConfigSource {
            properties = Map.copyOf(properties);
        }

        @Override
        public Map<String, String> getProperties() {
            return properties;
        }

        @Override
        public Set<String> getPropertyNames() {
            return properties.keySet();
        }

        @Override
        public String getValue(String propertyName) {
            return properties.get(propertyName);
        }

        @Override
        public String getName() {
            return "runtime-dependencies-validator-test";
        }
    }
}
