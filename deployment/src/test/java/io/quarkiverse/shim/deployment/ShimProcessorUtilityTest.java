package io.quarkiverse.shim.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;

import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

class ShimProcessorUtilityTest {

    @Test
    void reflectionRegistrationIncludesIndexedSuperclasses() throws IOException {
        Index index = Index.of(Parent.class, Child.class);

        assertEquals(List.of(Child.class.getName(), Parent.class.getName()),
                ShimProcessor.reflectionHierarchy(index, Child.class.getName()));
    }

    static class Parent {
        @SuppressWarnings("unused")
        private int inheritedPrivateField;
    }

    static final class Child extends Parent {
    }
}
