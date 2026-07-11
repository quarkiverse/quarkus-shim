package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Wraps the target method: the hook runs in place of it and decides whether,
 * when, and how to invoke the original body via a {@link ShimCall} handle. This
 * is the most general form of advice — it can inspect and replace arguments,
 * short-circuit the original, and transform the return value.
 * <p>
 * The annotated method must be {@code static}. Its parameters, in order, are:
 * <ul>
 * <li>a {@link ShimCall} handle to the original body, parameterized with the
 * target's boxed return type (or {@link Void});</li>
 * <li>for an <b>instance</b> target, a {@code self} parameter (the target
 * class or {@code Object}) receiving {@code this};</li>
 * <li>the target method's own parameters, matching positionally.</li>
 * </ul>
 * The hook's return type must match the target method's return type.
 * <p>
 * Example - timing and post-processing {@code String Greeter.greet(String)}:
 *
 * <pre>{@code
 * @ShimAround(method = "greet")
 * public static String greet(ShimCall<String> original, Greeter self, String name) {
 *     String result = original.proceed(); // run the real greet(name)
 *     return result.toUpperCase();
 * }
 * }</pre>
 *
 * {@code @ShimAround} cannot be combined with other hooks on the same target
 * method, and cannot target constructors. Note that {@code proceed()} always
 * runs the original with the arguments the target was called with; passing
 * different arguments is not supported in this version.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimAround {

    /** Name of the target method to wrap. Matches every overload unless disambiguated. */
    String method();

    /**
     * Optional JVM method descriptor to pin the wrap to a single overload.
     * Prefer {@link #paramTypes()} for readability.
     */
    String descriptor() default "";

    /**
     * Optional parameter types of the target overload to wrap, as class
     * literals — a readable alternative to {@link #descriptor()}.
     */
    Class<?>[] paramTypes() default {};
}
