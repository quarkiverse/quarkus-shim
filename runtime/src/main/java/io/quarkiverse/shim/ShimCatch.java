package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs this hook when the target method exits by throwing.
 * <p>
 * The exception is rethrown unchanged once the hook returns, so a catch-hook
 * observes without altering control flow — for logging, metrics, or capturing
 * diagnostic state a third-party library discards. To swallow or translate the
 * exception, throw a different one from the hook, or use {@link ShimAround},
 * which can also return a value instead.
 * <p>
 * The annotated method must be {@code static void}. Its parameters, in order,
 * are:
 * <ul>
 * <li>an optional {@code self} parameter (typed as the target class or
 * {@code Object}) that receives the target instance — allowed only when
 * the target method is an instance method;</li>
 * <li>an optional trailing parameter typed as {@link #exception()} (or any
 * supertype of it) that receives the exception being thrown.</li>
 * </ul>
 * Constructors cannot be hooked this way: a handler covering a constructor
 * body could observe {@code this} before {@code super()} has run, which the
 * JVM verifier rejects.
 *
 * @see ShimFinally
 * @see ShimPriority
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimCatch {

    /** Name of the target method to hook. Matches every overload unless disambiguated. */
    String method();

    /**
     * The exception type to hook. Defaults to {@link Throwable}, so the hook
     * sees every exceptional exit.
     */
    Class<? extends Throwable> exception() default Throwable.class;

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
