package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs this hook however the target method exits — before every normal return
 * and again if it exits by throwing, exactly like a {@code finally} block.
 * <p>
 * Use it to release or restore something regardless of outcome.
 * {@link ShimAfter} runs only on the normal path; {@link ShimCatch} only on the
 * exceptional one.
 * <p>
 * The annotated method must be {@code static void} and may declare a single
 * optional {@code self} parameter (typed as the target class or {@code Object}),
 * allowed only when the target method is an instance method. The value being
 * returned is not passed, because there is none on the exceptional path — use
 * {@link ShimAfter} when you need it.
 * <p>
 * Constructors cannot be hooked this way: a handler covering a constructor body
 * could observe {@code this} before {@code super()} has run, which the JVM
 * verifier rejects.
 *
 * @see ShimPriority
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimFinally {

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
