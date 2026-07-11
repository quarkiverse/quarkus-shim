package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs this hook just before every normal return of the target method.
 * It does not run when the target method exits by throwing.
 * <p>
 * The annotated method must be {@code static void}. Its parameters, in order,
 * are:
 * <ul>
 * <li>an optional {@code self} parameter (typed as the target class or
 * {@code Object}) that receives the target instance — allowed only when
 * the target method is an instance method;</li>
 * <li>an optional trailing {@code returned} parameter, typed as the target
 * method's return type (or {@code Object}), that receives the value about
 * to be returned. Not available for {@code void} targets.</li>
 * </ul>
 * To observe or alter arguments, or to change the returned value, use
 * {@link ShimAround} — an after-hook cannot modify the return value, and target
 * arguments are not passed to it because the method body may have reassigned
 * them by the time it returns.
 * <p>
 * Constructors can be hooked with {@code method = "<init>"} — the hook runs
 * after construction and may receive {@code self}, which pairs well with
 * {@link ShimFields} for post-construction fix-ups. Static initializers can be
 * hooked with {@code method = "<clinit>"}.
 *
 * @see ShimPriority
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimAfter {

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
     * literals — a readable alternative to {@link #descriptor()}.
     */
    Class<?>[] paramTypes() default {};
}
