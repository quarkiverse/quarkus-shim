package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces the entire body of the target method with a delegation to this
 * hook.
 * <p>
 * The annotated method must be {@code static} and its signature must mirror
 * the target method:
 * <ul>
 * <li>if the target is an <b>instance</b> method, the first parameter must be
 * the target class — or {@code Object}, when the target class is not
 * visible from your code — and receives {@code this}, followed by the
 * target's parameters;</li>
 * <li>if the target is <b>static</b>, the parameters must match exactly;</li>
 * <li>the return type must match the target's return type.</li>
 * </ul>
 * Example - replacing {@code String Greeter.greet(String name)}:
 *
 * <pre>{@code
 * @ShimReplace(method = "greet")
 * public static String greet(Greeter self, String name) {
 *     return "Patched " + name;
 * }
 * }</pre>
 *
 * To call the original from your replacement, use {@link ShimAround} instead.
 * <p>
 * Static initializers can be replaced with {@code method = "<clinit>"} (note
 * that static field initializers written at the declaration site are part of
 * {@code <clinit>} and are discarded too). Constructors can NOT be replaced —
 * the JVM requires every constructor to call {@code super()}/{@code this()}
 * before {@code this} can escape; use {@link ShimAfter} on {@code "<init>"}
 * instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimReplace {

    /** Name of the target method to replace. Matches every overload unless disambiguated. */
    String method();

    /**
     * Optional JVM method descriptor to pin the replacement to a single
     * overload. Prefer {@link #paramTypes()} for readability.
     */
    String descriptor() default "";

    /**
     * Optional parameter types of the target overload to replace, as class
     * literals — a readable alternative to {@link #descriptor()}.
     */
    Class<?>[] paramTypes() default {};
}
