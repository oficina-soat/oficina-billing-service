package br.com.oficina.billing.framework.web;

import br.com.oficina.billing.framework.messaging.AwsDomainMessagingClient;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;

@Startup
@ApplicationScoped
public class RuntimeDependenciesValidator {
    private static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 5;

    private final Config config;
    private final Runnable databaseProbe;
    private final Runnable messagingProbe;

    @Inject
    RuntimeDependenciesValidator(
            Config config,
            Instance<DataSource> dataSources,
            AwsDomainMessagingClient messagingClient) {
        this(config, () -> validateDatabase(dataSources), messagingClient::validateDependencies);
    }

    RuntimeDependenciesValidator(Config config, Runnable databaseProbe, Runnable messagingProbe) {
        this.config = config;
        this.databaseProbe = databaseProbe;
        this.messagingProbe = messagingProbe;
    }

    @PostConstruct
    void validateAtStartup() {
        validate(activeProfile());
    }

    void validate(String activeProfile) {
        var deploymentEnvironment = value(config, "oficina.observability.deployment-environment");
        if (!isProtectedRuntime(activeProfile, deploymentEnvironment)) {
            return;
        }

        var violations = RuntimeSettings.from(config).violations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Configuracao invalida para runtime protegido: " + String.join("; ", violations));
        }

        runProbe("PostgreSQL", databaseProbe);
        runProbe("SNS/SQS", messagingProbe);
    }

    static boolean isProtectedRuntime(String activeProfile, String deploymentEnvironment) {
        var normalizedProfile = normalize(activeProfile);
        return "prod".equalsIgnoreCase(normalizedProfile)
                || "lab".equalsIgnoreCase(normalizedProfile)
                || "lab".equalsIgnoreCase(normalize(deploymentEnvironment));
    }

    private String activeProfile() {
        return config.getOptionalValue("quarkus.profile", String.class)
                .filter(profile -> !profile.isBlank())
                .orElseGet(() -> LaunchMode.current() == LaunchMode.NORMAL
                        ? "prod"
                        : LaunchMode.current().name().toLowerCase(Locale.ROOT));
    }

    private static void runProbe(String dependency, Runnable probe) {
        try {
            probe.run();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Dependencia obrigatoria indisponivel na inicializacao: " + dependency + ".", exception);
        }
    }

    private static void validateDatabase(Instance<DataSource> dataSources) {
        if (dataSources.isUnsatisfied() || dataSources.isAmbiguous()) {
            throw new IllegalStateException("DataSource PostgreSQL nao esta disponivel.");
        }
        try (var connection = dataSources.get().getConnection()) {
            if (!connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS)) {
                throw new IllegalStateException("Conexao PostgreSQL nao passou na validacao.");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Falha ao validar a conexao PostgreSQL.", exception);
        }
    }

    private static String value(Config config, String propertyName) {
        return normalize(config.getOptionalValue(propertyName, String.class).orElse(""));
    }

    private static boolean booleanValue(Config config, String propertyName, boolean defaultValue) {
        return config.getOptionalValue(propertyName, Boolean.class).orElse(defaultValue);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    static final class RuntimeSettings {
        private final String persistenceKind;
        private final boolean dataSourceActive;
        private final boolean flywayActive;
        private final boolean migrateAtStart;
        private final String databaseUsername;
        private final String databasePassword;
        private final String jdbcDatabaseUrl;
        private final String reactiveDatabaseUrl;
        private final String authIssuer;
        private final String authAudience;
        private final String authPublicKeyLocation;
        private final boolean messagingEnabled;
        private final boolean publisherEnabled;
        private final boolean consumerEnabled;
        private final boolean workerEnabled;
        private final String awsRegion;
        private final String endpointOverride;
        private final String awsAccessKeyId;
        private final String awsSecretAccessKey;
        private final String awsSessionToken;
        private final boolean mercadoPagoEnabled;
        private final String mercadoPagoAccessToken;
        private final String mercadoPagoPayerEmail;
        private final String mercadoPagoApiUrl;

        private RuntimeSettings(Config config) {
            persistenceKind = value(config, "oficina.persistence.kind");
            dataSourceActive = booleanValue(config, "quarkus.datasource.active", true);
            flywayActive = booleanValue(config, "quarkus.flyway.active", true);
            migrateAtStart = booleanValue(config, "quarkus.flyway.migrate-at-start", false);
            databaseUsername = value(config, "quarkus.datasource.username");
            databasePassword = value(config, "quarkus.datasource.password");
            jdbcDatabaseUrl = value(config, "quarkus.datasource.jdbc.url");
            reactiveDatabaseUrl = value(config, "quarkus.datasource.reactive.url");
            authIssuer = value(config, "mp.jwt.verify.issuer");
            authAudience = value(config, "mp.jwt.verify.audiences");
            authPublicKeyLocation = value(config, "mp.jwt.verify.publickey.location");
            messagingEnabled = booleanValue(config, "oficina.messaging.enabled", false);
            publisherEnabled = booleanValue(config, "oficina.messaging.publisher.enabled", false);
            consumerEnabled = booleanValue(config, "oficina.messaging.consumer.enabled", false);
            workerEnabled = booleanValue(config, "oficina.messaging.worker.enabled", false);
            awsRegion = value(config, "AWS_REGION");
            endpointOverride = value(config, "oficina.messaging.endpoint-override");
            awsAccessKeyId = value(config, "oficina.messaging.aws-access-key-id");
            awsSecretAccessKey = value(config, "oficina.messaging.aws-secret-access-key");
            awsSessionToken = value(config, "oficina.messaging.aws-session-token");
            mercadoPagoEnabled = booleanValue(config, "oficina.mercado-pago.enabled", false);
            mercadoPagoAccessToken = value(config, "oficina.mercado-pago.access-token");
            mercadoPagoPayerEmail = value(config, "oficina.mercado-pago.payer-email");
            mercadoPagoApiUrl = value(config, "quarkus.rest-client.mercado-pago-api.url");
        }

        static RuntimeSettings from(Config config) {
            return new RuntimeSettings(config);
        }

        List<String> violations() {
            var violations = new ArrayList<String>();
            if (!"postgresql".equalsIgnoreCase(persistenceKind)) {
                violations.add("oficina.persistence.kind deve ser postgresql");
            }
            require(dataSourceActive, "quarkus.datasource.active deve permanecer habilitado", violations);
            require(flywayActive, "quarkus.flyway.active deve permanecer habilitado", violations);
            require(migrateAtStart, "quarkus.flyway.migrate-at-start deve permanecer habilitado", violations);
            require(databaseUsername, "DB_USERNAME", violations);
            require(databasePassword, "DB_PASSWORD", violations);
            require(jdbcDatabaseUrl, "JDBC_DATABASE_URL", violations);
            require(reactiveDatabaseUrl, "REACTIVE_DATABASE_URL", violations);
            require(authIssuer, "OFICINA_AUTH_ISSUER", violations);
            if (!"oficina-billing-service".equals(authAudience)) {
                violations.add("OFICINA_AUTH_AUDIENCE deve ser oficina-billing-service");
            }
            require(authPublicKeyLocation, "MP_JWT_VERIFY_PUBLICKEY_LOCATION", violations);
            require(messagingEnabled, "OFICINA_MESSAGING_ENABLED deve permanecer habilitado", violations);
            require(publisherEnabled, "publisher SNS deve permanecer habilitado", violations);
            require(consumerEnabled, "consumer SQS deve permanecer habilitado", violations);
            require(workerEnabled, "worker de mensageria deve permanecer habilitado", violations);
            require(awsRegion, "AWS_REGION", violations);
            if (!endpointOverride.isBlank()) {
                violations.add("OFICINA_MESSAGING_ENDPOINT_OVERRIDE nao e permitido");
            }
            validateAwsCredentials(violations);
            if (mercadoPagoEnabled) {
                require(mercadoPagoAccessToken, "OFICINA_MERCADO_PAGO_ACCESS_TOKEN", violations);
                require(mercadoPagoPayerEmail, "OFICINA_MERCADO_PAGO_PAYER_EMAIL", violations);
                require(mercadoPagoApiUrl, "OFICINA_MERCADO_PAGO_API_URL", violations);
            }
            return List.copyOf(violations);
        }

        private void validateAwsCredentials(List<String> violations) {
            var accessKeyConfigured = !awsAccessKeyId.isBlank();
            var secretKeyConfigured = !awsSecretAccessKey.isBlank();
            var sessionTokenConfigured = !awsSessionToken.isBlank();
            if (accessKeyConfigured != secretKeyConfigured || (sessionTokenConfigured && !accessKeyConfigured)) {
                violations.add("credenciais AWS estaticas devem informar access key, secret key e session token quando aplicavel");
            }
            if (isPlaceholder(awsAccessKeyId) || isPlaceholder(awsSecretAccessKey) || isPlaceholder(awsSessionToken)) {
                violations.add("credenciais AWS estaticas nao podem conter placeholder");
            }
        }

        private static void require(boolean condition, String property, List<String> violations) {
            if (!condition) {
                violations.add(property);
            }
        }

        private static void require(String value, String property, List<String> violations) {
            require(!value.isBlank() && !isPlaceholder(value), property + " deve ser informado sem placeholder", violations);
        }

        private static boolean isPlaceholder(String value) {
            return value.toUpperCase(Locale.ROOT).contains("PLACEHOLDER");
        }
    }
}
