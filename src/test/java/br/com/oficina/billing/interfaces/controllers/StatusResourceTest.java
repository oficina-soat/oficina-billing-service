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
                .body("environment", equalTo("lab"))
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
    void shouldRequireIdempotencyKeyForMutatingRequests() {
        given()
                .when()
                .post("/api/v1/status")
                .then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("errorCode", equalTo("HTTP_400"))
                .body("correlationId", notNullValue());
    }
}
