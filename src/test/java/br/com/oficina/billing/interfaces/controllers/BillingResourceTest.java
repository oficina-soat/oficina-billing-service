package br.com.oficina.billing.interfaces.controllers;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BillingResourceTest {
    @Test
    void shouldRunBudgetApprovalAndPaymentFlow() {
        var ordemServicoId = UUID.randomUUID().toString();

        var orcamentoId = given()
                .header("X-Idempotency-Key", "orcamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "ordemServicoId": "%s"
                        }
                        """.formatted(ordemServicoId))
                .when()
                .post("/api/v1/orcamentos")
                .then()
                .statusCode(201)
                .header("Location", notNullValue())
                .body("orcamentoId", notNullValue())
                .body("ordemServicoId", equalTo(ordemServicoId))
                .body("status", equalTo("GERADO"))
                .body("itens", hasSize(1))
                .body("valorTotal", equalTo(0))
                .extract()
                .path("orcamentoId")
                .toString();

        given()
                .when()
                .get("/api/v1/orcamentos/{orcamentoId}", orcamentoId)
                .then()
                .statusCode(200)
                .body("orcamentoId", equalTo(orcamentoId))
                .body("status", equalTo("GERADO"));

        given()
                .when()
                .get("/api/v1/ordens-servico/{ordemServicoId}/orcamentos", ordemServicoId)
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].orcamentoId", equalTo(orcamentoId));

        given()
                .header("X-Idempotency-Key", "aprovar-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "motivo": "Aprovado pelo cliente"
                        }
                        """)
                .when()
                .post("/api/v1/orcamentos/{orcamentoId}/aprovacao", orcamentoId)
                .then()
                .statusCode(200)
                .body("status", equalTo("APROVADO"));

        var pagamentoId = given()
                .header("X-Idempotency-Key", "pagamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "ordemServicoId": "%s",
                          "orcamentoId": "%s",
                          "valor": 0,
                          "metodo": "PIX"
                        }
                        """.formatted(ordemServicoId, orcamentoId))
                .when()
                .post("/api/v1/pagamentos")
                .then()
                .statusCode(201)
                .header("Location", notNullValue())
                .body("pagamentoId", notNullValue())
                .body("ordemServicoId", equalTo(ordemServicoId))
                .body("orcamentoId", equalTo(orcamentoId))
                .body("status", equalTo("CRIADO"))
                .body("metodo", equalTo("PIX"))
                .extract()
                .path("pagamentoId")
                .toString();

        given()
                .header("X-Idempotency-Key", "confirmar-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "provedor": "mercado-pago",
                          "transacaoExternaId": "mp-test-001"
                        }
                        """)
                .when()
                .post("/api/v1/pagamentos/{pagamentoId}/confirmacao", pagamentoId)
                .then()
                .statusCode(200)
                .body("status", equalTo("CONFIRMADO"))
                .body("provedor", equalTo("mercado-pago"))
                .body("transacaoExternaId", equalTo("mp-test-001"));

        given()
                .when()
                .get("/api/v1/ordens-servico/{ordemServicoId}/pagamentos", ordemServicoId)
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].pagamentoId", equalTo(pagamentoId));
    }

    @Test
    void shouldReplayBudgetCreationAndRejectDivergentPayload() {
        var ordemServicoId = UUID.randomUUID().toString();
        var idempotencyKey = "orcamento-replay-" + UUID.randomUUID();
        var requestBody = """
                {
                  "ordemServicoId": "%s"
                }
                """.formatted(ordemServicoId);

        var orcamentoId = given()
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/v1/orcamentos")
                .then()
                .statusCode(201)
                .body("orcamentoId", notNullValue())
                .extract()
                .path("orcamentoId")
                .toString();

        given()
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body(requestBody)
                .when()
                .post("/api/v1/orcamentos")
                .then()
                .statusCode(201)
                .body("orcamentoId", equalTo(orcamentoId))
                .body("ordemServicoId", equalTo(ordemServicoId));

        given()
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body("""
                        {
                          "ordemServicoId": "%s"
                        }
                        """.formatted(UUID.randomUUID()))
                .when()
                .post("/api/v1/orcamentos")
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_CONFLICT"))
                .body("message", equalTo("Chave de idempotencia reutilizada com payload divergente."));
    }

    @Test
    void shouldRejectPaymentForBudgetNotApproved() {
        var ordemServicoId = UUID.randomUUID().toString();
        var orcamentoId = criarOrcamento(ordemServicoId);

        given()
                .header("X-Idempotency-Key", "pagamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "ordemServicoId": "%s",
                          "orcamentoId": "%s",
                          "valor": 0,
                          "metodo": "PIX"
                        }
                        """.formatted(ordemServicoId, orcamentoId))
                .when()
                .post("/api/v1/pagamentos")
                .then()
                .statusCode(409)
                .body("code", equalTo("INVALID_STATE_TRANSITION"))
                .body("message", equalTo("Somente orcamentos aprovados podem receber pagamento."));
    }

    @Test
    void shouldRefuseCreatedPayment() {
        var pagamento = criarPagamentoCriado();

        given()
                .header("X-Idempotency-Key", "recusar-pagamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "provedor": "mercado-pago",
                          "motivo": "Pagamento recusado pelo provedor"
                        }
                        """)
                .when()
                .post("/api/v1/pagamentos/{pagamentoId}/recusa", pagamento.pagamentoId())
                .then()
                .statusCode(200)
                .body("pagamentoId", equalTo(pagamento.pagamentoId()))
                .body("status", equalTo("RECUSADO"))
                .body("provedor", equalTo("mercado-pago"));

        given()
                .when()
                .get("/api/v1/pagamentos/{pagamentoId}", pagamento.pagamentoId())
                .then()
                .statusCode(200)
                .body("status", equalTo("RECUSADO"));
    }

    @Test
    void shouldCancelCreatedPayment() {
        var pagamento = criarPagamentoCriado();

        given()
                .header("X-Idempotency-Key", "cancelar-pagamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "motivo": "Cancelado antes da captura"
                        }
                        """)
                .when()
                .post("/api/v1/pagamentos/{pagamentoId}/cancelamento", pagamento.pagamentoId())
                .then()
                .statusCode(200)
                .body("pagamentoId", equalTo(pagamento.pagamentoId()))
                .body("status", equalTo("CANCELADO"));

        given()
                .when()
                .get("/api/v1/pagamentos/{pagamentoId}", pagamento.pagamentoId())
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELADO"));
    }

    @Test
    void shouldRejectSecondBudgetDecision() {
        var ordemServicoId = UUID.randomUUID().toString();
        var orcamentoId = criarOrcamento(ordemServicoId);

        given()
                .header("X-Idempotency-Key", "aprovar-" + UUID.randomUUID())
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/v1/orcamentos/{orcamentoId}/aprovacao", orcamentoId)
                .then()
                .statusCode(200)
                .body("status", equalTo("APROVADO"));

        given()
                .header("X-Idempotency-Key", "recusar-" + UUID.randomUUID())
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/v1/orcamentos/{orcamentoId}/recusa", orcamentoId)
                .then()
                .statusCode(409)
                .body("code", equalTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    void shouldReturnNotFoundForUnknownBudget() {
        given()
                .when()
                .get("/api/v1/orcamentos/{orcamentoId}", UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("code", equalTo("RESOURCE_NOT_FOUND"))
                .body("message", equalTo("Orcamento nao encontrado."));
    }

    @Test
    void shouldValidateBudgetRequest() {
        given()
                .header("X-Idempotency-Key", "orcamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/v1/orcamentos")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Campo ordemServicoId e obrigatorio."));
    }

    private String criarOrcamento(String ordemServicoId) {
        return given()
                .header("X-Idempotency-Key", "orcamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "ordemServicoId": "%s"
                        }
                        """.formatted(ordemServicoId))
                .when()
                .post("/api/v1/orcamentos")
                .then()
                .statusCode(201)
                .extract()
                .path("orcamentoId")
                .toString();
    }

    private PagamentoCriado criarPagamentoCriado() {
        var ordemServicoId = UUID.randomUUID().toString();
        var orcamentoId = criarOrcamento(ordemServicoId);
        aprovarOrcamento(orcamentoId);
        var pagamentoId = given()
                .header("X-Idempotency-Key", "pagamento-" + UUID.randomUUID())
                .contentType("application/json")
                .body("""
                        {
                          "ordemServicoId": "%s",
                          "orcamentoId": "%s",
                          "valor": 0,
                          "metodo": "PIX"
                        }
                        """.formatted(ordemServicoId, orcamentoId))
                .when()
                .post("/api/v1/pagamentos")
                .then()
                .statusCode(201)
                .extract()
                .path("pagamentoId")
                .toString();
        return new PagamentoCriado(pagamentoId);
    }

    private void aprovarOrcamento(String orcamentoId) {
        given()
                .header("X-Idempotency-Key", "aprovar-" + UUID.randomUUID())
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/v1/orcamentos/{orcamentoId}/aprovacao", orcamentoId)
                .then()
                .statusCode(200);
    }

    private record PagamentoCriado(String pagamentoId) {
    }
}
