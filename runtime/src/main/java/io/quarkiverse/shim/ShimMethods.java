package io.quarkiverse.shim;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invoke methods — including private ones — of shimmed classes from hook
 * bodies.
 * <p>
 * Overloads are resolved from the runtime types of the arguments, following
 * Java's own rules closely enough for ordinary code: widening primitive
 * conversion is applied, the most specific applicable overload wins, and
 * varargs methods are considered when nothing matches by fixed arity. When
 * that is still ambiguous — most often because an argument is {@code null} —
 * use {@link #invokeExact} with an explicit signature.
 * <p>
 * Lookups are cached. Every {@code @Shim} target class is automatically
 * registered for reflection, so this also works in a GraalVM native image.
 * Methods declared in indexed superclasses of the target are found and those
 * superclasses are registered for native reflection as well; interface default
 * methods are found too.
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

    /** Widening primitive conversions, as JLS 5.1.2. */
    private static final Map<Class<?>, Set<Class<?>>> WIDENS_TO = Map.of(
            byte.class, Set.of(short.class, int.class, long.class, float.class, double.class),
            short.class, Set.of(int.class, long.class, float.class, double.class),
            char.class, Set.of(int.class, long.class, float.class, double.class),
            int.class, Set.of(long.class, float.class, double.class),
            long.class, Set.of(float.class, double.class),
            float.class, Set.of(double.class));

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
    public static <T> T invokeStaticExact(Class<?> owner, String methodName, Class<?>[] parameterTypes,
            Object... args) {
        return doInvokeExact(owner, null, methodName, parameterTypes, args);
    }

    /**
     * Constructs an instance of {@code owner}, including via a private
     * constructor, resolving the constructor from the runtime argument types.
     */
    public static <T> T newInstance(Class<T> owner, Object... args) {
        Constructor<?> constructor = resolveConstructor(owner, args);
        try {
            @SuppressWarnings("unchecked")
            T created = (T) constructor.newInstance(args);
            return created;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Cannot instantiate " + owner.getName()
                    + " (it is abstract, an interface, or an array type)", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access the constructor of " + owner.getName(), e);
        } catch (InvocationTargetException e) {
            throw sneakyThrow(e.getCause());
        }
    }

    private static <T> T doInvoke(Class<?> owner, Object instance, String methodName, Object... args) {
        Method method = resolve(owner, methodName, args);
        return invokeResolved(owner, instance, methodName, method, packVarargs(method, args));
    }

    /**
     * Reflection needs the trailing arguments of a varargs call already
     * collected into an array; a source-level call site would have javac do it.
     */
    private static Object[] packVarargs(Method method, Object[] args) {
        if (!method.isVarArgs()) {
            return args;
        }
        Class<?>[] parameters = method.getParameterTypes();
        Class<?> arrayType = parameters[parameters.length - 1];
        if (args.length == parameters.length) {
            Object last = args[args.length - 1];
            if (last == null || arrayType.isInstance(last)) {
                return args; // already passed as an array
            }
        }
        int fixed = parameters.length - 1;
        Object[] packed = new Object[parameters.length];
        System.arraycopy(args, 0, packed, 0, fixed);
        Object trailing = java.lang.reflect.Array.newInstance(arrayType.getComponentType(), args.length - fixed);
        for (int i = fixed; i < args.length; i++) {
            java.lang.reflect.Array.set(trailing, i - fixed, args[i]);
        }
        packed[fixed] = trailing;
        return packed;
    }

    private static <T> T doInvokeExact(Class<?> owner, Object instance, String methodName, Class<?>[] parameterTypes,
            Object[] args) {
        if (parameterTypes.length != args.length) {
            throw new IllegalArgumentException("Parameter type count (" + parameterTypes.length
                    + ") does not match argument count (" + args.length + ") for '" + methodName + "'");
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == null) {
                throw new IllegalArgumentException("parameterTypes[" + i + "] is null for '" + methodName
                        + "'; every parameter type must be given explicitly");
            }
        }
        Method method = resolveExact(owner, methodName, parameterTypes);
        return invokeResolved(owner, instance, methodName, method, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeResolved(Class<?> owner, Object instance, String methodName, Method method,
            Object[] args) {
        if (instance == null && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("'" + methodName + "' on " + owner.getName()
                    + " is an instance method; use invoke/invokeExact with the target instance"
                    + " rather than invokeStatic/invokeStaticExact");
        }
        try {
            return (T) method.invoke(instance, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke '" + methodName + "' on " + owner.getName(), e);
        } catch (InvocationTargetException e) {
            // rethrow exactly what the target threw: this helper stands in for a
            // direct call, so wrapping would change the shimmed method's contract
            throw sneakyThrow(e.getCause());
        }
    }

    private static Method resolve(Class<?> owner, String methodName, Object[] args) {
        MethodKey key = new MethodKey(methodName, argumentKey(args), false);
        Map<MethodKey, Method> cache = cacheFor(owner);
        Method cached = cache == null ? null : cache.get(key);
        if (cached != null) {
            return cached;
        }
        Method resolved = findMethod(owner, methodName, args);
        resolved.setAccessible(true);
        if (cache != null) {
            cache.put(key, resolved);
        }
        return resolved;
    }

    private static Method findMethod(Class<?> owner, String methodName, Object[] args) {
        List<Method> named = new ArrayList<>();
        for (Class<?> declaring : hierarchyOf(owner)) {
            List<Method> atThisLevel = new ArrayList<>();
            for (Method method : declaring.getDeclaredMethods()) {
                // a bridge always delegates to the real method, and accepts the
                // same arguments, so keeping it only creates false ambiguity
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                if (method.getName().equals(methodName)) {
                    named.add(method);
                    if (accepts(method, args, false)) {
                        atThisLevel.add(method);
                    }
                }
            }
            if (!atThisLevel.isEmpty()) {
                return pickMostSpecific(atThisLevel, owner, methodName, args);
            }
        }
        // nothing matched by fixed arity; a varargs method may still apply
        List<Method> varargs = new ArrayList<>();
        for (Method method : named) {
            if (method.isVarArgs() && accepts(method, args, true)) {
                varargs.add(method);
            }
        }
        if (varargs.size() == 1) {
            return varargs.get(0);
        }
        if (varargs.size() > 1) {
            throw new IllegalArgumentException("Ambiguous varargs method '" + methodName + "' on " + owner.getName()
                    + " for (" + describeArguments(args) + "): " + describeCandidates(varargs)
                    + ". Use invokeExact/invokeStaticExact with explicit parameter types.");
        }
        throw new IllegalArgumentException("No method '" + methodName + "' on " + owner.getName()
                + " accepting (" + describeArguments(args) + ")"
                + (named.isEmpty()
                        ? " — no method of that name was found at all"
                        : ". Candidates: " + describeCandidates(named)));
    }

    /**
     * The most specific applicable overload wins, as it would in Java source.
     * Only a genuinely unordered set is reported as ambiguous.
     */
    private static Method pickMostSpecific(List<Method> candidates, Class<?> owner, String methodName, Object[] args) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        Method best = null;
        for (Method candidate : candidates) {
            if (best == null || isMoreSpecific(candidate, best)) {
                best = candidate;
            }
        }
        for (Method candidate : candidates) {
            if (candidate != best && !isMoreSpecific(best, candidate)) {
                throw new IllegalArgumentException("Ambiguous method '" + methodName + "' on " + owner.getName()
                        + " for (" + describeArguments(args) + "): " + describeCandidates(candidates)
                        + ". Use invokeExact/invokeStaticExact with explicit parameter types.");
            }
        }
        return best;
    }

    private static boolean isMoreSpecific(Method candidate, Method other) {
        Class<?>[] a = candidate.getParameterTypes();
        Class<?>[] b = other.getParameterTypes();
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!isAssignable(b[i], a[i])) {
                return false;
            }
        }
        return true;
    }

    private static Method resolveExact(Class<?> owner, String methodName, Class<?>[] parameterTypes) {
        MethodKey key = new MethodKey(methodName, List.of(parameterTypes.clone()), true);
        Map<MethodKey, Method> cache = cacheFor(owner);
        Method cached = cache == null ? null : cache.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> declaring : hierarchyOf(owner)) {
            try {
                Method method = declaring.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                if (cache != null) {
                    cache.put(key, method);
                }
                return method;
            } catch (NoSuchMethodException notOnThisClass) {
                // keep walking the hierarchy
            }
        }
        throw new IllegalArgumentException("No method '" + methodName + "' on " + owner.getName()
                + " with parameter types " + List.of(parameterTypes));
    }

    private static Constructor<?> resolveConstructor(Class<?> owner, Object[] args) {
        List<Constructor<?>> matches = new ArrayList<>();
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            if (!constructor.isSynthetic() && accepts(constructor, args, false)) {
                matches.add(constructor);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No constructor on " + owner.getName()
                    + " accepting (" + describeArguments(args) + ")");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous constructor on " + owner.getName()
                    + " for (" + describeArguments(args) + "): " + matches);
        }
        Constructor<?> constructor = matches.get(0);
        constructor.setAccessible(true);
        return constructor;
    }

    /**
     * Superclasses first (nearest declaration wins), then interfaces, so
     * default methods and interface constants are reachable.
     */
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

    private static boolean accepts(Executable method, Object[] args, boolean varargs) {
        Class<?>[] parameters = method.getParameterTypes();
        if (varargs) {
            if (args.length < parameters.length - 1) {
                return false;
            }
            for (int i = 0; i < parameters.length - 1; i++) {
                if (!acceptsArgument(parameters[i], args[i])) {
                    return false;
                }
            }
            Class<?> component = parameters[parameters.length - 1].getComponentType();
            for (int i = parameters.length - 1; i < args.length; i++) {
                if (!acceptsArgument(component, args[i])) {
                    return false;
                }
            }
            return true;
        }
        if (parameters.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (!acceptsArgument(parameters[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean acceptsArgument(Class<?> parameter, Object arg) {
        if (arg == null) {
            return !parameter.isPrimitive();
        }
        return isAssignable(parameter, arg.getClass());
    }

    /**
     * Whether an argument of runtime type {@code argType} (always a reference
     * type, so primitives arrive boxed) can be passed for {@code parameter}.
     * Reflection performs unboxing and widening primitive conversion itself.
     */
    private static boolean isAssignable(Class<?> parameter, Class<?> argType) {
        if (!parameter.isPrimitive()) {
            Class<?> boxed = argType.isPrimitive() ? WRAPPERS.get(argType) : argType;
            return parameter.isAssignableFrom(boxed);
        }
        Class<?> argPrimitive = argType.isPrimitive() ? argType : unwrap(argType);
        if (argPrimitive == null) {
            return false;
        }
        return parameter == argPrimitive
                || WIDENS_TO.getOrDefault(argPrimitive, Set.of()).contains(parameter);
    }

    private static Class<?> unwrap(Class<?> wrapper) {
        for (Map.Entry<Class<?>, Class<?>> entry : WRAPPERS.entrySet()) {
            if (entry.getValue() == wrapper) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Classes loaded by the bootstrap loader are never unloaded, so caching
     * against them would pin whatever application classes appear in the key —
     * across dev-mode restarts, indefinitely. Those lookups are rare enough to
     * resolve every time.
     */
    private static Map<MethodKey, Method> cacheFor(Class<?> owner) {
        return owner.getClassLoader() == null ? null : CACHE.get(owner);
    }

    private static List<Class<?>> argumentKey(Object[] args) {
        List<Class<?>> types = new ArrayList<>(args.length);
        for (Object arg : args) {
            types.add(arg == null ? NullArgument.class : arg.getClass());
        }
        return List.copyOf(types);
    }

    private static String describeArguments(Object[] args) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Object arg : args) {
            joiner.add(arg == null ? "null" : arg.getClass().getSimpleName());
        }
        return joiner.toString();
    }

    private static String describeCandidates(List<Method> candidates) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (Method candidate : candidates) {
            StringJoiner params = new StringJoiner(", ", "(", ")");
            for (Class<?> parameter : candidate.getParameterTypes()) {
                params.add(parameter.getSimpleName());
            }
            String signature = candidate.getName() + params;
            unique.putIfAbsent(signature, signature);
        }
        return String.join(", ", unique.keySet());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private record MethodKey(String name, List<Class<?>> parameterTypes, boolean exact) {
    }

    private static final class NullArgument {
        private NullArgument() {
        }
    }
}
