package io.quarkiverse.shim;

/**
 * Handle to the original method body, passed as the first parameter of a
 * {@link ShimAround} hook. Call {@link #proceed()} to run the original
 * behavior; the shim decides whether, when, and with what surrounding logic to
 * do so.
 *
 * @param <T> the boxed return type of the target method ({@link Void} for
 *        {@code void} methods, in which case {@code proceed()} returns
 *        {@code null})
 */
@FunctionalInterface
public interface ShimCall<T> {

    /** Runs the original method body and returns its (boxed) result. */
    T proceed();
}
