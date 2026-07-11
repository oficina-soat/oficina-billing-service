package br.com.oficina.billing.contracts;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.oficina.billing.framework.messaging.DomainEventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlatformContractsTest {
    private static final Path CONTRACTS_DIR = Path.of("..", "oficina-platform", "contracts");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern OPENAPI_PATH = Pattern.compile("^  (/[^:]+):\\s*$");
    private static final Pattern OPENAPI_METHOD = Pattern.compile("^    (get|post|put|patch|delete):\\s*$");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "eventId", "eventType", "eventVersion", "occurredAt", "producer", "aggregateId", "payload");

    @Test
    void deveExporOperacoesDoOpenApiCanonico() throws IOException {
        var expectedOperations = canonicalOpenApiOperations("openapi/oficina-billing-service.yaml");
        var runtimeOperations = runtimeOpenApiOperations();

        expectedOperations.forEach((path, methods) -> {
            assertTrue(runtimeOperations.containsKey(path), () -> "Rota ausente no OpenAPI gerado: " + path);
            assertTrue(runtimeOperations.get(path).containsAll(methods),
                    () -> "Metodos ausentes no OpenAPI gerado para " + path + ": " + methods);
        });
    }

    @Test
    void deveAplicarContratosDeErroEIdempotencia() {
        given()
                .header("X-Correlation-Id", "contract-correlation-billing")
                .when()
                .post("/api/v1/status")
                .then()
                .statusCode(400)
                .header("X-Correlation-Id", equalTo("contract-correlation-billing"))
                .body("timestamp", notNullValue())
                .body("status", equalTo(400))
                .body("error", equalTo("Bad Request"))
                .body("code", equalTo("IDEMPOTENCY_KEY_REQUIRED"))
                .body("path", equalTo("/api/v1/status"))
                .body("correlationId", equalTo("contract-correlation-billing"))
                .body("service", equalTo("oficina-billing-service"))
                .body("details.size()", equalTo(0));
    }

    @Test
    void deveValidarEnvelopeLocalContraSchemaComumDeEventos() throws IOException {
        var commonSchema = readJsonContract("events/schemas/common.schema.json");
        var required = fieldNames(commonSchema.at("/$defs/eventEnvelope/required"));
        var recordFields = Arrays.stream(DomainEventEnvelope.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(ENVELOPE_FIELDS, required);
        assertEquals(ENVELOPE_FIELDS, recordFields);
    }

    @Test
    void deveValidarEventosProduzidosEConsumidosContraSchemasJson() {
        producedEvents().forEach((eventType, topic) ->
                assertEventSchema(eventType, "oficina-billing-service", topic));

        consumedEvents().forEach((eventType, producer) ->
                assertEventSchema(eventType, producer, null));
    }

    @Test
    void deveValidarParticipacaoNoContratoDaSaga() throws IOException {
        var sagaContract = Files.readString(contract("saga/oficina-os-saga-v1.md"));

        Set.of(
                "gerar-orcamento",
                "solicitar-pagamento",
                "orcamentoGerado",
                "orcamentoAprovado",
                "orcamentoRecusado",
                "pagamentoSolicitado",
                "pagamentoConfirmado",
                "pagamentoRecusado")
                .forEach(term -> assertTrue(sagaContract.contains(term),
                        () -> "Termo esperado ausente do contrato da Saga: " + term));
    }

    private static Map<String, String> producedEvents() {
        return Map.of(
                "orcamentoGerado", "oficina.billing.orcamento-gerado",
                "orcamentoAprovado", "oficina.billing.orcamento-aprovado",
                "orcamentoRecusado", "oficina.billing.orcamento-recusado",
                "pagamentoSolicitado", "oficina.billing.pagamento-solicitado",
                "pagamentoConfirmado", "oficina.billing.pagamento-confirmado",
                "pagamentoRecusado", "oficina.billing.pagamento-recusado");
    }

    private static Map<String, String> consumedEvents() {
        return Map.ofEntries(
                Map.entry("ordemDeServicoCriada", "oficina-os-service"),
                Map.entry("pecaIncluidaNaOrdemDeServico", "oficina-os-service"),
                Map.entry("servicoIncluidoNaOrdemDeServico", "oficina-os-service"),
                Map.entry("diagnosticoFinalizado", "oficina-execution-service"),
                Map.entry("execucaoFinalizada", "oficina-execution-service"),
                Map.entry("ordemDeServicoFinalizada", "oficina-os-service"),
                Map.entry("ordemDeServicoEntregue", "oficina-os-service"),
                Map.entry("estoqueAcrescentado", "oficina-execution-service"),
                Map.entry("estoqueBaixado", "oficina-execution-service"),
                Map.entry("sagaCompensada", "oficina-os-service"),
                Map.entry("sagaFinalizadaComSucesso", "oficina-os-service"));
    }

    private static void assertEventSchema(String eventType, String producer, String topic) {
        try {
            var schema = readJsonContract("events/schemas/" + eventType + ".schema.json");

            assertEquals(eventType, schema.path("title").asText());
            assertEquals(eventType, schema.at("/properties/eventType/const").asText());
            assertEquals(1, schema.at("/properties/eventVersion/const").asInt());
            assertEquals(producer, schema.at("/properties/producer/const").asText());
            if (topic != null) {
                assertEquals(topic, schema.path("x-topic").asText());
            }
        } catch (IOException exception) {
            throw new AssertionError("Falha ao ler schema do evento " + eventType, exception);
        }
    }

    private static Map<String, Set<String>> runtimeOpenApiOperations() throws IOException {
        var body = given()
                .accept(ContentType.JSON)
                .queryParam("format", "json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        var operations = new LinkedHashMap<String, Set<String>>();
        var paths = MAPPER.readTree(body).path("paths");
        paths.properties().forEach(entry -> {
            var methods = new LinkedHashSet<String>();
            entry.getValue().properties().forEach(method -> methods.add(method.getKey()));
            operations.put(normalizeApiPath(entry.getKey()), methods);
        });
        return operations;
    }

    private static Map<String, Set<String>> canonicalOpenApiOperations(String relativePath) throws IOException {
        var operations = new LinkedHashMap<String, Set<String>>();
        String currentPath = null;

        for (String line : Files.readAllLines(contract(relativePath))) {
            var pathMatcher = OPENAPI_PATH.matcher(line);
            if (pathMatcher.matches()) {
                currentPath = pathMatcher.group(1);
                operations.putIfAbsent(currentPath, new LinkedHashSet<>());
                continue;
            }

            var methodMatcher = OPENAPI_METHOD.matcher(line);
            if (currentPath != null && methodMatcher.matches()) {
                operations.get(currentPath).add(methodMatcher.group(1));
            }
        }

        return operations;
    }

    private static Set<String> fieldNames(JsonNode arrayNode) {
        var values = new LinkedHashSet<String>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static JsonNode readJsonContract(String relativePath) throws IOException {
        return MAPPER.readTree(contract(relativePath).toFile());
    }

    private static Path contract(String relativePath) {
        var path = CONTRACTS_DIR.resolve(relativePath).normalize();
        assertTrue(Files.isRegularFile(path), () -> "Contrato nao encontrado: " + path.toAbsolutePath());
        return path;
    }

    private static String normalizeApiPath(String path) {
        return path.startsWith("/api/v1") ? path.substring("/api/v1".length()) : path;
    }
}
