package br.com.oficina.billing.framework.messaging;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

public class LocalStackMessagingTestResource implements QuarkusTestResourceLifecycleManager {
    private static final Region REGION = Region.US_EAST_1;
    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new GenericContainer<>("localstack/localstack:3.8")
                .withEnv("SERVICES", "sns,sqs")
                .withExposedPorts(4566)
                .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));
        container.start();
        var endpoint = endpoint();
        try (var sns = snsClient(endpoint); var sqs = sqsClient(endpoint)) {
            routes().forEach(route -> createRoute(sns, sqs, route));
        }
        System.setProperty("oficina.localstack.endpoint", endpoint);
        return Map.of(
                "oficina.messaging.publisher.enabled", "true",
                "oficina.messaging.consumer.enabled", "true",
                "oficina.messaging.worker.enabled", "false",
                "oficina.messaging.endpoint-override", endpoint,
                "oficina.messaging.consumer.wait-time-seconds", "0",
                "oficina.messaging.publisher.max-attempts", "1");
    }

    @Override
    public void stop() {
        System.clearProperty("oficina.localstack.endpoint");
        if (container != null) {
            container.stop();
        }
    }

    static SqsClient sqsClient() {
        return sqsClient(System.getProperty("oficina.localstack.endpoint"));
    }

    static SnsClient snsClient() {
        return snsClient(System.getProperty("oficina.localstack.endpoint"));
    }

    static String queueName(String topic, String consumer) {
        return physicalName(topic + "." + consumer);
    }

    static String topicArn(String topic) {
        return "arn:aws:sns:us-east-1:000000000000:" + physicalName(topic);
    }

    private String endpoint() {
        return "http://localhost:" + container.getMappedPort(4566);
    }

    private static SnsClient snsClient(String endpoint) {
        return SnsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
    }

    private static SqsClient sqsClient(String endpoint) {
        return SqsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
    }

    private static void createRoute(SnsClient sns, SqsClient sqs, Route route) {
        var topicArn = sns.createTopic(builder -> builder.name(physicalName(route.topic()))).topicArn();
        var dlqUrl = sqs.createQueue(builder -> builder.queueName(physicalName(route.topic() + ".dlq"))).queueUrl();
        var dlqArn = queueArn(sqs, dlqUrl);
        for (var consumer : route.consumers()) {
            var queueUrl = sqs.createQueue(builder -> builder
                    .queueName(queueName(route.topic(), consumer))
                    .attributes(Map.of(
                            QueueAttributeName.REDRIVE_POLICY,
                            "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"5\"}".formatted(dlqArn))))
                    .queueUrl();
            var queueArn = queueArn(sqs, queueUrl);
            sns.subscribe(SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(Map.of("RawMessageDelivery", "true"))
                    .build());
        }
    }

    private static String queueArn(SqsClient sqs, String queueUrl) {
        return sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build()).attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static String physicalName(String logicalName) {
        return logicalName.replace('.', '-');
    }

    private static List<Route> routes() {
        return List.of(
                new Route("oficina.os.ordem-de-servico-criada", List.of("oficina-billing-service", "oficina-execution-service")),
                new Route("oficina.execution.diagnostico-iniciado", List.of("oficina-os-service")),
                new Route("oficina.os.peca-incluida-na-ordem-de-servico", List.of("oficina-billing-service", "oficina-execution-service")),
                new Route("oficina.os.servico-incluido-na-ordem-de-servico", List.of("oficina-billing-service", "oficina-execution-service")),
                new Route("oficina.execution.diagnostico-finalizado", List.of("oficina-os-service", "oficina-billing-service")),
                new Route("oficina.billing.orcamento-gerado", List.of("oficina-os-service")),
                new Route("oficina.billing.orcamento-aprovado", List.of("oficina-os-service", "oficina-execution-service")),
                new Route("oficina.billing.orcamento-recusado", List.of("oficina-os-service")),
                new Route("oficina.execution.execucao-iniciada", List.of("oficina-os-service")),
                new Route("oficina.execution.execucao-finalizada", List.of("oficina-os-service", "oficina-billing-service")),
                new Route("oficina.os.ordem-de-servico-finalizada", List.of("oficina-billing-service", "oficina-execution-service")),
                new Route("oficina.os.ordem-de-servico-entregue", List.of("oficina-billing-service")),
                new Route("oficina.billing.pagamento-solicitado", List.of("oficina-os-service")),
                new Route("oficina.billing.pagamento-confirmado", List.of("oficina-os-service")),
                new Route("oficina.billing.pagamento-recusado", List.of("oficina-os-service")),
                new Route("oficina.execution.estoque-acrescentado", List.of("oficina-billing-service")),
                new Route("oficina.execution.estoque-baixado", List.of("oficina-billing-service")),
                new Route("oficina.saga.saga-compensada", List.of("oficina-billing-service", "oficina-execution-service")),
                new Route("oficina.saga.saga-finalizada-com-sucesso", List.of("oficina-billing-service", "oficina-execution-service")));
    }

    private record Route(String topic, List<String> consumers) {
    }
}
