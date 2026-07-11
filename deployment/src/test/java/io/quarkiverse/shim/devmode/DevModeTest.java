package io.quarkiverse.shim.devmode;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

/**
 * Confirms the extension works under dev mode's split classloader setup, and
 * that the transform is re-applied across a live reload.
 */
public class DevModeTest {

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addClasses(DevGreeter.class, DevGreeterShim.class, DevGreetResource.class));

    @Test
    void shimAppliesInDevModeAndSurvivesLiveReload() {
        // @ShimAround upper-cases the original "Hello world"
        when().get("/greet").then().statusCode(200).body(is("HELLO WORLD"));

        // trigger a live reload (re-augmentation); the shim must be re-applied
        TEST.modifySourceFile("DevGreetResource.java", s -> s.replace("\"world\"", "\"there\""));
        when().get("/greet").then().statusCode(200).body(is("HELLO THERE"));
    }
}
