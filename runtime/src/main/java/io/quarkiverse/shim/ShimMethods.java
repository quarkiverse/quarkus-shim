package io.quarkiverse.shim;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invoke methods — including private ones — of shimmed classes from hook
 * bodies.
 * <p>
 * Lookups are cached. Every {@code @Shim} target class is automatically
 * registered for reflection, so this also works in a GraalVM native image.
 * Methods declared in superclasses of the target are found, but only the
 * target class itself is registered for native reflection.
 */
public final class ShimMethods {

    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class);

    private ShimMethods() {
    }

    /**
     * Invokes an instance method resolved by name and argument types, e.g.
     * {@code String s = ShimMethods.invoke(self, "decorate", "value");}.
     */
    public static <T> T invoke(Object instance, String methodName, Object... args) {
        return doInvoke(instance.getClass(), instance, methodName, args);
    }

    /** Invokes a static method resolved by name and argument types. */
    public static <T> T invokeStatic(Class<?> owner, String methodName, Object... args) {
        return doInvoke(owner, null, methodName, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T doInvoke(Class<?> owner, Object instance, String methodName, Object... args) {
        Method method = resolve(owner, methodName, args);
        try {
            return (T) method.invoke(instance, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke '" + methodName + "' on " + owner, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("'" + methodName + "' on " + owner + " threw a checked exception", cause);
        }
    }

    private static Method resolve(Class<?> owner, String methodName, Object[] args) {
        StringBuilder key = new StringBuilder(owner.getName()).append('#').append(methodName);
        for (Object arg : args) {
            key.append('|').append(arg == null ? "null" : arg.getClass().getName());
        }
        return CACHE.computeIfAbsent(key.toString(), k -> {
            List<Method> matches = new ArrayList<>();
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals(methodName) && accepts(method, args)) {
                        matches.add(method);
                    }
                }
                if (!matches.isEmpty()) {
                    break; // nearest declaration wins
                }
            }
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("No method '" + methodName + "' on " + owner
                        + " accepting " + args.length + " argument(s) of the given types");
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("Ambiguous method '" + methodName + "' on " + owner
                        + ": " + matches + ". Disambiguate by casting arguments to the exact parameter types.");
            }
            Method method = matches.get(0);
            method.setAccessible(true);
            return method;
        });
    }

    private static boolean accepts(Method method, Object[] args) {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) {
                if (parameters[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> parameter = parameters[i].isPrimitive() ? WRAPPERS.get(parameters[i]) : parameters[i];
            if (!parameter.isAssignableFrom(args[i].getClass())) {
                return false;
            }
        }
        return true;
    }
}
