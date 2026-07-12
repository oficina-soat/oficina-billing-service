package br.com.oficina.billing.interfaces.controllers;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StatusResourceTest {
    @Test
    void shouldExposeServiceStatusAndCorrelationId() {
        given()
                .header("X-Correlation-Id", "test-correlation-id")
                .when()
                .get("/api/v1/status")
                .then()
                .statusCode(200)
                .header("X-Correlation-Id", equalTo("test-correlation-id"))
                .body("service", equalTo("oficina-billing-service"))
                .body("environment", equalTo("test"))
                .body("status", equalTo("UP"));
    }

    @Test
    void shouldGenerateCorrelationIdWhenHeaderIsMissing() {
        given()
                .when()
                .get("/api/v1/status")
                .then()
                .statusCode(200)
                .header("X-Correlation-Id", notNullValue());
    }

    @Test
    void shouldAcceptCanonicalIdempotencyKeyForMutatingRequests() {
        given()
                .header("X-Idempotency-Key", "status-canonical-001")
                .when()
                .post("/api/v1/status")
                .then()
                .statusCode(200)
                .body("service", equalTo("oficina-billing-service"))
                .body("environment", equalTo("test"))
                .body("status", equalTo("UP"));
    }

    @Test
    void shouldAcceptLegacyIdempotencyKeyForMutatingRequests() {
        given()
                .header("Idempotency-Key", "status-legacy-001")
                .when()
                .post("/api/v1/status")
                .then()
                .statusCode(200)
                .body("service", equalTo("oficina-billing-service"))
                .body("environment", equalTo("test"))
                .body("status", equalTo("UP"));
    }

    @Test
    void shouldRequireIdempotencyKeyForMutatingRequests() {
        given()
                .when()
                .post("/api/v1/status")
                .then()
                .statusCode(400)
                .body("timestamp", notNullValue())
                .body("status", equalTo(400))
                .body("error", equalTo("Bad Request"))
                .body("code", equalTo("IDEMPOTENCY_KEY_REQUIRED"))
                .body("message", equalTo("Header X-Idempotency-Key obrigatorio para operacoes mutaveis."))
                .body("path", equalTo("/api/v1/status"))
                .body("correlationId", notNullValue())
                .body("service", equalTo("oficina-billing-service"))
                .body("details.size()", equalTo(0));
    }

    @Test
    void shouldReturnContractedErrorForUnknownApi() {
        given()
                .when()
                .get("/api/v1/status-inexistente")
                .then()
                .statusCode(404)
                .body("timestamp", notNullValue())
                .body("status", equalTo(404))
                .body("error", equalTo("Not Found"))
                .body("code", equalTo("RESOURCE_NOT_FOUND"))
                .body("message", notNullValue())
                .body("path", equalTo("/api/v1/status-inexistente"))
                .body("correlationId", notNullValue())
                .body("service", equalTo("oficina-billing-service"))
                .body("details.size()", equalTo(0));
    }
}
