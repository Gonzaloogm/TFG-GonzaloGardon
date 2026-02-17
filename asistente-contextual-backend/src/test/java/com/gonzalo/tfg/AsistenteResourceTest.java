package com.gonzalo.tfg;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * Tests de integración para el Asistente Contextual Backend.
 *
 * Notación: AsistenteResource fue eliminado y reemplazado por:
 * - ChatWebSocket → ws://localhost:8080/chat
 * - HealthResource → GET /api/health
 */
@QuarkusTest
class AsistenteResourceTest {

    @Test
    void testHealthEndpoint() {
        given().when()
                .get("/api/health")
                .then()
                .statusCode(200)
                .body("status", is("OK"))
                .body("service", notNullValue());
    }

    @Test
    void testHealthEndpointDevuelveJson() {
        given()
                .when()
                .get("/api/health")
                .then()
                .statusCode(200)
                .contentType("application/json");
    }
}