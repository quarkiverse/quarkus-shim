package io.quarkiverse.shim.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ShimResourceTest {

    @Test
    public void testHelloEndpoint() {
        given()
                .when().get("/shim")
                .then()
                .statusCode(200)
                .body(is("Hello shim"));
    }
}
