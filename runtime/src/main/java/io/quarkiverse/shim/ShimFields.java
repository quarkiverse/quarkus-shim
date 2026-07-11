package io.quarkiverse.shim;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read and write fields — including private ones — of shimmed classes from
 * hook bodies.
 * <p>
 * Lookups are cached. Every {@code @Shim} target class is automatically
 * registered for reflection, so this also works in a GraalVM native image.
 * Fields declared in superclasses of the target are found, but only the target
 * class itself is registered for native reflection.
 */
public final class ShimFields {

    private static final Map<String, Field> CACHE = new ConcurrentHashMap<>();

    private ShimFields() {
    }

    /** Reads an instance field, e.g. {@code int count = ShimFields.get(self, "count");}. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Object instance, String fieldName) {
        try {
            return (T) field(instance.getClass(), fieldName).get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field '" + fieldName + "' of " + instance.getClass(), e);
        }
    }

    /** Writes an instance field. */
    public static void set(Object instance, String fieldName, Object value) {
        try {
            field(instance.getClass(), fieldName).set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write field '" + fieldName + "' of " + instance.getClass(), e);
        }
    }

    /** Reads a static field. */
    @SuppressWarnings("unchecked")
    public static <T> T getStatic(Class<?> owner, String fieldName) {
        try {
            return (T) field(owner, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read static field '" + fieldName + "' of " + owner, e);
        }
    }

    /** Writes a static field. */
    public static void setStatic(Class<?> owner, String fieldName, Object value) {
        try {
            field(owner, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write static field '" + fieldName + "' of " + owner, e);
        }
    }

    private static Field field(Class<?> owner, String fieldName) {
        return CACHE.computeIfAbsent(owner.getName() + '#' + fieldName, key -> {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // keep walking up the hierarchy
                }
            }
            throw new IllegalArgumentException("No field '" + fieldName + "' on " + owner + " or its superclasses");
        });
    }
}
