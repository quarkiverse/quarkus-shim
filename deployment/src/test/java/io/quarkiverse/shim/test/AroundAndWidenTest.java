package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * @ShimAround (instance / static / void targets), paramTypes overload
 *             selection, and widenAccess.
 */
public class AroundAndWidenTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Calculator.class, CalculatorShim.class, CallLog.class));

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void aroundInstanceMethodWrapsResult() {
        // original add(2,3)=5; around doubles it
        assertEquals(10, new Calculator().add(2, 3));
    }

    @Test
    void aroundStaticMethodWrapsResult() {
        // original tag("a")="<a>"; around upper-cases it
        assertEquals("<A>", Calculator.tag("a"));
    }

    @Test
    void aroundVoidMethodRunsBeforeAndAfterOriginal() {
        new Calculator().note("m");
        assertEquals(List.of("around-before", "note:m", "around-after"), CallLog.entries());
    }

    @Test
    void paramTypesSelectsOneOverload() {
        Calculator calc = new Calculator();
        assertEquals("INT[5]", calc.format(5)); // replaced
        assertEquals("str:hi", calc.format("hi")); // untouched
    }

    @Test
    void widenAccessMakesPrivateMembersReflectableWithoutSetAccessible() throws Exception {
        Field base = Calculator.class.getDeclaredField("base");
        assertFalse(Modifier.isPrivate(base.getModifiers()), "field should have been widened");
        assertEquals(100, base.getInt(new Calculator())); // no setAccessible

        Method secret = Calculator.class.getDeclaredMethod("secret");
        assertFalse(Modifier.isPrivate(secret.getModifiers()), "method should have been widened");
        assertEquals(7, (int) secret.invoke(new Calculator())); // no setAccessible
    }
}
