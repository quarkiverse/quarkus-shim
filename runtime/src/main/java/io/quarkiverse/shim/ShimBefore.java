package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs this hook at the entry of the target method, before any of its
 * original code executes.
 * <p>
 * The annotated method must be {@code static void}. Its parameters, in order,
 * are:
 * <ul>
 * <li>an optional {@code self} parameter (typed as the target class or
 * {@code Object}) that receives the target instance — allowed only when
 * the target method is an instance method;</li>
 * <li>followed by a prefix of the target method's own parameters (matching
 * positionally by type). You may take all of them, the first few, or
 * none.</li>
 * </ul>
 * Arguments are read at method entry, where they still hold the caller-supplied
 * values.
 * <p>
 * Constructors can be hooked with {@code method = "<init>"} (the hook runs
 * before {@code super()}, so {@code self} is not allowed — {@code this} is not
 * initialized yet) and static initializers with {@code method = "<clinit>"}.
 *
 * @see ShimPriority
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimBefore {

    /** Name of the target method to hook. Matches every overload unless disambiguated. */
    String method();

    /**
     * Optional JVM method descriptor (e.g. {@code "(Ljava/lang/String;)V"}) to
     * pin the hook to a single overload. Prefer {@link #paramTypes()} for
     * readability.
     */
    String descriptor() default "";

    /**
     * Optional parameter types of the target overload to hook, as class
     * literals — a readable alternative to {@link #descriptor()}. For example
     * {@code paramTypes = {String.class, int.class}} pins the hook to the
     * overload taking {@code (String, int)}.
     */
    Class<?>[] paramTypes() default {};
}
