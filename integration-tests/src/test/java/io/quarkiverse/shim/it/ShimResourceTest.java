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

    @Test
    public void testReplaceInstanceMethodWithPrivateAccess() {
        // @ShimReplace on Greeter.greet: reads/writes the private greetCount
        // field and calls the private decorate method via ShimFields/ShimMethods
        given()
                .when().get("/shim/greet/Ann")
                .then()
                .statusCode(200)
                .body(is("[Patched Ann #1]"));
    }

    @Test
    public void testReplaceStaticMethod() {
        // @ShimReplace on static Greeter.answer: original returns -1
        given()
                .when().get("/shim/answer")
                .then()
                .statusCode(200)
                .body(is("42"));
    }

    @Test
    public void testAroundCallsOriginalAndTransformsResult() {
        // @ShimAround on Greeter.shout: proceed() runs the original ("hi Bob"),
        // then the hook upper-cases and appends "!"
        given()
                .when().get("/shim/shout/Bob")
                .then()
                .statusCode(200)
                .body(is("HI BOB!"));
    }

    @Test
    public void testBeforeAfterOrderingAndArguments() {
        // two @ShimBefore hooks ordered by @ShimPriority (1 then 2), the first
        // receiving the target's argument, then the original body, then
        // @ShimAfter receiving the value about to be returned
        given()
                .when().get("/shim/task/job")
                .then()
                .statusCode(200)
                .body(is("before1:job,before2,body:job,after:result:job"));
    }

    @Test
    public void testDefinalizeAndConstructorAfterHook() {
        // Widget.name is final in source; definalize strips the modifier and
        // the <init> after-hook rewrites it post-construction
        given()
                .when().get("/shim/widget/gear")
                .then()
                .statusCode(200)
                .body(is("gear-patched"));
    }

    @Test
    public void testOverloadPinningAndDisabledShim() {
        // format(int) is replaced (pinned via paramTypes); format(String) is
        // targeted only by DisabledFormatterShim, which application.properties
        // disables — so it keeps its original body
        given()
                .when().get("/shim/format")
                .then()
                .statusCode(200)
                .body(is("patched-int:7|str:x"));
    }
}
