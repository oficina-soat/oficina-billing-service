package br.com.oficina.billing.framework.messaging;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sts.StsClient;

@ApplicationScoped
public class AwsDomainMessagingClient {
    private static final String LOCALSTACK_ACCOUNT_ID = "000000000000";

    private final String region;
    private final String endpointOverride;
    private final String configuredAccountId;
    private final SnsClient snsClient;
    private final SqsClient sqsClient;
    private final StsClient stsClient;
    private final Map<String, String> queueUrls = new HashMap<>();
    private String resolvedAccountId;

    AwsDomainMessagingClient(
            @ConfigProperty(name = "quarkus.application.name") String applicationName,
            @ConfigProperty(name = "AWS_REGION", defaultValue = "us-east-1") String region,
            @ConfigProperty(name = "oficina.messaging.endpoint-override") Optional<String> endpointOverride,
            @ConfigProperty(name = "oficina.messaging.aws-account-id") Optional<String> configuredAccountId,
            @ConfigProperty(name = "oficina.messaging.aws-access-key-id") Optional<String> accessKeyId,
            @ConfigProperty(name = "oficina.messaging.aws-secret-access-key") Optional<String> secretAccessKey,
            @ConfigProperty(name = "oficina.messaging.aws-session-token") Optional<String> sessionToken) {
        this.region = region;
        this.endpointOverride = endpointOverride.orElse("");
        this.configuredAccountId = configuredAccountId.orElse("");
        var credentialsProvider = credentialsProvider(
                accessKeyId.orElse(""),
                secretAccessKey.orElse(""),
                sessionToken.orElse(""),
                this.endpointOverride);
        this.snsClient = snsClient(region, this.endpointOverride, credentialsProvider);
        this.sqsClient = sqsClient(region, this.endpointOverride, credentialsProvider);
        this.stsClient = stsClient(region, credentialsProvider);
        if (!DomainMessagingRoutes.SERVICE_NAME.equals(applicationName)) {
            throw new IllegalStateException("Servico de mensageria configurado com nome invalido: " + applicationName);
        }
    }

    void publish(String topic, String message, Map<String, String> attributes) {
        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn(topic))
                .message(message)
                .messageAttributes(messageAttributes(attributes))
                .build());
    }

    ReceivedMessages receive(String topic, int maxMessages, int waitTimeSeconds) {
        var queueUrl = queueUrl(topic);
        var request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(Math.clamp(maxMessages, 1, 10))
                .waitTimeSeconds(Math.clamp(waitTimeSeconds, 0, 20))
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .build();
        return new ReceivedMessages(queueUrl, sqsClient.receiveMessage(request).messages());
    }

    void delete(String queueUrl, Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    public void validateDependencies() {
        accountId();
        for (var topic : DomainMessagingRoutes.producedTopics()) {
            snsClient.getTopicAttributes(builder -> builder.topicArn(topicArn(topic)));
        }
        for (var topic : DomainMessagingRoutes.consumedTopics()) {
            queueUrl(topic);
        }
    }

    @PreDestroy
    void close() {
        snsClient.close();
        sqsClient.close();
        stsClient.close();
    }

    private String topicArn(String topic) {
        return "arn:aws:sns:%s:%s:%s".formatted(region, accountId(), DomainMessagingRoutes.physicalName(topic));
    }

    private String queueUrl(String topic) {
        var queueName = DomainMessagingRoutes.queueName(topic);
        synchronized (queueUrls) {
            return queueUrls.computeIfAbsent(queueName, name -> sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                    .queueName(name)
                    .build()).queueUrl());
        }
    }

    private String accountId() {
        if (!configuredAccountId.isBlank()) {
            return configuredAccountId;
        }
        if (!endpointOverride.isBlank()) {
            return LOCALSTACK_ACCOUNT_ID;
        }
        if (resolvedAccountId == null) {
            resolvedAccountId = stsClient.getCallerIdentity().account();
        }
        return resolvedAccountId;
    }

    private static Map<String, MessageAttributeValue> messageAttributes(Map<String, String> attributes) {
        var result = new HashMap<String, MessageAttributeValue>();
        attributes.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                result.put(key, MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(value)
                        .build());
            }
        });
        return Map.copyOf(result);
    }

    static AwsCredentialsProvider credentialsProvider(
            String accessKeyId,
            String secretAccessKey,
            String sessionToken,
            String endpointOverride) {
        if (!endpointOverride.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"));
        }
        var accessKeyConfigured = !accessKeyId.isBlank();
        var secretKeyConfigured = !secretAccessKey.isBlank();
        if (accessKeyConfigured != secretKeyConfigured || (!sessionToken.isBlank() && !accessKeyConfigured)) {
            throw new IllegalArgumentException("Credenciais AWS estaticas incompletas.");
        }
        if (accessKeyConfigured && !sessionToken.isBlank()) {
            return StaticCredentialsProvider.create(AwsSessionCredentials.create(
                    accessKeyId,
                    secretAccessKey,
                    sessionToken));
        }
        if (accessKeyConfigured) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }
        return DefaultCredentialsProvider.create();
    }

    private static SnsClient snsClient(String region, String endpointOverride, AwsCredentialsProvider credentialsProvider) {
        var builder = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

    private static SqsClient sqsClient(String region, String endpointOverride, AwsCredentialsProvider credentialsProvider) {
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

    private static StsClient stsClient(String region, AwsCredentialsProvider credentialsProvider) {
        return StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    record ReceivedMessages(String queueUrl, List<Message> messages) {
    }
}
