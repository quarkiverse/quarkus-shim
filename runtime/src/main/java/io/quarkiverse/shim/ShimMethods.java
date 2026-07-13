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
 * Methods declared in indexed superclasses of the target are found and those
 * superclasses are registered for native reflection as well.
 */
public final class ShimMethods {

    private static final ClassValue<Map<MethodKey, Method>> CACHE = new ClassValue<>() {
        @Override
        protected Map<MethodKey, Method> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

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

    /**
     * Invokes an instance method using an exact parameter signature. Use this
     * when overload resolution from runtime argument values would be ambiguous,
     * particularly when one or more arguments are {@code null}.
     */
    public static <T> T invokeExact(Object instance, String methodName, Class<?>[] parameterTypes, Object... args) {
        return doInvokeExact(instance.getClass(), instance, methodName, parameterTypes, args);
    }

    /** Invokes a static method using an exact parameter signature. */
    public static <T> T invokeStaticExact(Class<?> owner, String methodName, Class<?>[] parameterTypes, Object... args) {
        return doInvokeExact(owner, null, methodName, parameterTypes, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T doInvoke(Class<?> owner, Object instance, String methodName, Object... args) {
        Method method = resolve(owner, methodName, args);
        return invokeResolved(owner, instance, methodName, method, args);
    }

    private static <T> T doInvokeExact(Class<?> owner, Object instance, String methodName, Class<?>[] parameterTypes,
            Object[] args) {
        if (parameterTypes.length != args.length) {
            throw new IllegalArgumentException("Parameter type count does not match argument count for '"
                    + methodName + "'");
        }
        Method method = resolveExact(owner, methodName, parameterTypes);
        return invokeResolved(owner, instance, methodName, method, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeResolved(Class<?> owner, Object instance, String methodName, Method method, Object[] args) {
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
        List<Class<?>> argumentTypes = new ArrayList<>(args.length);
        for (Object arg : args) {
            argumentTypes.add(arg == null ? NullArgument.class : arg.getClass());
        }
        MethodKey key = new MethodKey(methodName, List.copyOf(argumentTypes), false);
        return CACHE.get(owner).computeIfAbsent(key, k -> {
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
                        + ": " + matches + ". Use invokeExact/invokeStaticExact with explicit parameter types.");
            }
            Method method = matches.get(0);
            method.setAccessible(true);
            return method;
        });
    }

    private static Method resolveExact(Class<?> owner, String methodName, Class<?>[] parameterTypes) {
        MethodKey key = new MethodKey(methodName, List.of(parameterTypes.clone()), true);
        return CACHE.get(owner).computeIfAbsent(key, ignored -> {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    Method method = c.getDeclaredMethod(methodName, parameterTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException notOnThisClass) {
                    // keep walking up the hierarchy
                }
            }
            throw new IllegalArgumentException("No method '" + methodName + "' on " + owner
                    + " with parameter types " + List.of(parameterTypes));
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

    private record MethodKey(String name, List<Class<?>> parameterTypes, boolean exact) {
    }

    private static final class NullArgument {
        private NullArgument() {
        }
    }
}
