package io.quarkiverse.shim;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read and write fields — including private ones — of shimmed classes from
 * hook bodies.
 * <p>
 * Lookups are cached. Every {@code @Shim} target class is automatically
 * registered for reflection, so this also works in a GraalVM native image.
 * Fields declared in indexed superclasses of the target are found and those
 * superclasses are registered for native reflection as well; constants declared
 * on implemented interfaces are found too.
 * <p>
 * Note that lookups start from the runtime class of the instance, so a subclass
 * field that shadows one declared on the target class takes precedence. Use
 * {@link #getDeclared} / {@link #setDeclared} to name the declaring class when
 * that matters.
 */
public final class ShimFields {

    private static final ClassValue<Map<String, Field>> CACHE = new ClassValue<>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

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
        write(field(instance.getClass(), fieldName), instance, instance.getClass(), fieldName, value);
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
        write(field(owner, fieldName), null, owner, fieldName, value);
    }

    /**
     * Reads a field declared by exactly {@code declaringClass}, ignoring any
     * field of the same name shadowing it on a subclass.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getDeclared(Class<?> declaringClass, Object instance, String fieldName) {
        try {
            return (T) declaredField(declaringClass, fieldName).get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field '" + fieldName + "' of " + declaringClass, e);
        }
    }

    /** Writes a field declared by exactly {@code declaringClass}. */
    public static void setDeclared(Class<?> declaringClass, Object instance, String fieldName, Object value) {
        write(declaredField(declaringClass, fieldName), instance, declaringClass, fieldName, value);
    }

    private static void write(Field field, Object instance, Class<?> owner, String fieldName, Object value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(writeFailureMessage(field, owner, fieldName), e);
        }
    }

    /**
     * A rejected write is nearly always a {@code final} field the shim forgot
     * to definalize, so say so instead of leaving the user with the JDK's
     * message.
     */
    private static String writeFailureMessage(Field field, Class<?> owner, String fieldName) {
        String base = "Cannot write field '" + fieldName + "' of " + owner;
        if (Modifier.isFinal(field.getModifiers())) {
            return base + ": it is final. List it in @Shim(definalize = { \"" + fieldName + "\" }) so the"
                    + " transformer strips the modifier at build time, which is more reliable than reflective"
                    + " final-field mutation";
        }
        return base;
    }

    private static Field declaredField(Class<?> declaringClass, String fieldName) {
        Map<String, Field> cache = cacheFor(declaringClass);
        String key = "=" + fieldName; // distinct from the hierarchy-walking lookup
        Field cached = cache == null ? null : cache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Field field = declaringClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            if (cache != null) {
                cache.put(key, field);
            }
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("No field '" + fieldName + "' declared on " + declaringClass, e);
        } catch (RuntimeException e) {
            throw accessFailure(declaringClass, fieldName, e);
        }
    }

    private static Field field(Class<?> owner, String fieldName) {
        Map<String, Field> cache = cacheFor(owner);
        Field cached = cache == null ? null : cache.get(fieldName);
        if (cached != null) {
            return cached;
        }
        Field resolved = findField(owner, fieldName);
        if (cache != null) {
            cache.put(fieldName, resolved);
        }
        return resolved;
    }

    private static Field findField(Class<?> owner, String fieldName) {
        for (Class<?> declaring : hierarchyOf(owner)) {
            try {
                Field field = declaring.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            } catch (RuntimeException e) {
                throw accessFailure(declaring, fieldName, e);
            }
        }
        throw new IllegalArgumentException("No field '" + fieldName + "' on " + owner
                + ", its superclasses, or its interfaces");
    }

    /**
     * {@code setAccessible} throws {@code InaccessibleObjectException} for a
     * strongly encapsulated module. Raw, that says nothing about which shim
     * caused it or what to do next.
     */
    private static RuntimeException accessFailure(Class<?> declaring, String fieldName, RuntimeException cause) {
        return new IllegalStateException("Cannot access field '" + fieldName + "' of " + declaring
                + ": its module does not open the package to this code. Shim can only patch classes loaded"
                + " through the Quarkus ClassLoader; JDK and other strongly encapsulated classes are out of"
                + " reach (" + cause + ")", cause);
    }

    /** Superclasses first, then interfaces, so interface constants are reachable. */
    private static List<Class<?>> hierarchyOf(Class<?> owner) {
        List<Class<?>> ordered = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            if (seen.add(c)) {
                ordered.add(c);
            }
        }
        List<Class<?>> queue = new ArrayList<>(ordered);
        for (int i = 0; i < queue.size(); i++) {
            for (Class<?> itf : queue.get(i).getInterfaces()) {
                if (seen.add(itf)) {
                    ordered.add(itf);
                    queue.add(itf);
                }
            }
        }
        return ordered;
    }

    /**
     * Classes loaded by the bootstrap loader are never unloaded, so caching
     * against them would retain application classes indefinitely.
     */
    private static Map<String, Field> cacheFor(Class<?> owner) {
        return owner.getClassLoader() == null ? null : CACHE.get(owner);
    }
}
