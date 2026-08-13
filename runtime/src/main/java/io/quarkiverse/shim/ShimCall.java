package io.quarkiverse.shim;

/**
 * Handle to the original method body, passed as the first parameter of a
 * {@link ShimAround} hook. Call {@link #proceed()} to run the original
 * behavior; the shim decides whether, when, and with what surrounding logic to
 * do so.
 * <p>
 * {@link #proceed(Object...)} runs the original with different arguments,
 * which is how a hook rewrites what the target sees:
 *
 * <pre>{@code
 * @ShimAround(method = "connect")
 * public static Conn connect(ShimCall<Conn> original, Client self, String url, int timeoutMs) {
 *     return original.proceed(url, Math.max(timeoutMs, 5_000)); // clamp a bad default
 * }
 * }</pre>
 *
 * @param <T> the boxed return type of the target method ({@link Void} for
 *        {@code void} methods, in which case {@code proceed()} returns
 *        {@code null})
 */
@FunctionalInterface
public interface ShimCall<T> {

    /**
     * Runs the original method body, optionally with replacement arguments.
     * <p>
     * This is the method the generated call site implements. Prefer
     * {@link #proceed()} and {@link #proceed(Object...)}, which name the two
     * cases and carry the return type.
     *
     * @param replacementArguments the arguments to run the original with, or
     *        {@code null} to reuse the ones the target was called with
     * @return the original's result, boxed; {@code null} for a {@code void} target
     */
    Object invokeWith(Object[] replacementArguments);

    /** Runs the original method body with the arguments the target was called with. */
    @SuppressWarnings("unchecked")
    default T proceed() {
        return (T) invokeWith(null);
    }

    /**
     * Runs the original method body with the given arguments in place of the
     * ones the target was called with.
     * <p>
     * The arguments must match the target method's parameter list in count and
     * type; primitives are unboxed, so a {@code null} for a primitive
     * parameter, a wrong count, or an incompatible type fails at this call.
     *
     * @param arguments replacement arguments, matching the target's parameters
     */
    @SuppressWarnings("unchecked")
    default T proceed(Object... arguments) {
        return (T) invokeWith(arguments == null ? new Object[0] : arguments);
    }
}
