package io.quarkiverse.shim.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A generic class that also implements a generic interface, so javac emits both
 * type-variable signatures and a bridge method. Shimming the plain method used
 * to fail the build on the generic ones.
 */
public class Repository<K, V> implements Function<K, String> {

    private final Map<K, V> entries = new LinkedHashMap<>();

    public V get(K key) {
        return entries.get(key);
    }

    public <U> U coerce(Class<U> type, Object value) {
        return type.cast(value);
    }

    @Override
    public String apply(K key) {
        return "applied:" + key;
    }

    public String describe() {
        return "vendor";
    }
}
