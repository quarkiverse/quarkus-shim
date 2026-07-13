package io.quarkiverse.shim.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimMethods;

class ReflectionCacheRegressionTest {

    @Test
    void fieldCacheSeparatesClassesWithTheSameNameFromDifferentClassLoaders() throws Exception {
        byte[] bytes = fixtureBytes();
        String name = Fixture.class.getName();
        Object first = new ChildFirstLoader(name, bytes).loadClass(name).getConstructor().newInstance();
        Object second = new ChildFirstLoader(name, bytes).loadClass(name).getConstructor().newInstance();

        assertEquals(42, ShimFields.<Integer> get(first, "value"));
        assertEquals(42, ShimFields.<Integer> get(second, "value"));
    }

    @Test
    void methodCacheSeparatesClassesWithTheSameNameFromDifferentClassLoaders() throws Exception {
        byte[] bytes = fixtureBytes();
        String name = Fixture.class.getName();
        Object first = new ChildFirstLoader(name, bytes).loadClass(name).getConstructor().newInstance();
        Object second = new ChildFirstLoader(name, bytes).loadClass(name).getConstructor().newInstance();

        assertEquals("ok", ShimMethods.<String> invoke(first, "message"));
        assertEquals("ok", ShimMethods.<String> invoke(second, "message"));
    }

    @Test
    void exactInvocationDisambiguatesNullArguments() {
        Fixture fixture = new Fixture();

        assertEquals("string", ShimMethods.<String> invokeExact(fixture, "pick",
                new Class<?>[] { String.class }, (Object) null));
        assertEquals("integer", ShimMethods.<String> invokeExact(fixture, "pick",
                new Class<?>[] { Integer.class }, (Object) null));
    }

    private static byte[] fixtureBytes() throws IOException {
        String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
        try (InputStream input = Fixture.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing fixture bytecode " + resource);
            }
            return input.readAllBytes();
        }
    }

    public static class Fixture {
        public int value = 42;

        public String message() {
            return "ok";
        }

        public String pick(String value) {
            return "string";
        }

        public String pick(Integer value) {
            return "integer";
        }
    }

    private static final class ChildFirstLoader extends ClassLoader {
        private final String childClassName;
        private final byte[] bytes;

        ChildFirstLoader(String childClassName, byte[] bytes) {
            super(ReflectionCacheRegressionTest.class.getClassLoader());
            this.childClassName = childClassName;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(childClassName)) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}
