package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this class contains shim hooks for another class.
 * <p>
 * The target class is patched at build time: methods annotated with
 * {@link ShimBefore}, {@link ShimAfter}, {@link ShimReplace} or
 * {@link ShimAround} in this class are woven into the target's bytecode during
 * Quarkus augmentation. {@link ShimAnnotate} may additionally attach
 * annotations to the target class and its methods or fields.
 * <p>
 * Specify the target either by class literal ({@link #value()}) or, when the
 * class is not visible from your code, by fully-qualified name
 * ({@link #targetName()}).
 * <p>
 * Tip: declaring the shim class in the <em>same package</em> as the target
 * grants it ordinary package-level access — package-private classes and
 * members, and {@code protected} members, become directly usable from hook
 * bodies (application and dependency classes share the Quarkus ClassLoader, so
 * they end up in the same runtime package). Combine with {@link #widenAccess()}
 * to also reach {@code private} members directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Shim {

    /** The class to patch. */
    Class<?> value() default void.class;

    /** Fully-qualified name of the class to patch, e.g. {@code "com.acme.internal.Foo"}. */
    String targetName() default "";

    /**
     * Optional logical name for this shim, used to gate it individually via
     * configuration: {@code quarkus.shim."<name>".enabled=false} disables just
     * this shim class while leaving the rest active. Defaults to the simple
     * name of the shim class.
     */
    String name() default "";

    /**
     * Names of fields on the target class whose {@code final} modifier should
     * be removed during transformation, making them writable after
     * construction via {@link ShimFields} without relying on reflective
     * final-field mutation (which the JDK is progressively restricting).
     * <p>
     * Static compile-time constants (e.g. {@code static final int X = 5})
     * cannot be definalized: javac inlines their value into every reader at
     * compile time, so rewriting the field would not affect existing readers —
     * the build fails instead.
     * <p>
     * Note: removing {@code final} forfeits the memory-model final-field
     * safe-publication guarantee for that field. This only matters when
     * instances are shared across threads via data races — and any
     * post-construction mutation gives that guarantee up anyway.
     */
    String[] definalize() default {};

    /**
     * When {@code true}, the {@code private} and {@code final} modifiers are
     * stripped from every declared member of the target class (compile-time
     * constant fields excepted).
     * <p>
     * The members become {@code public}, so they can be read and invoked
     * reflectively <em>without</em> {@code setAccessible(true)} — useful as the
     * JDK progressively restricts {@code setAccessible} and reflective final
     * mutation — and are directly accessible to separately-compiled code in the
     * same package. (Note: a shim's own source still cannot reference members
     * that were {@code private} in the target's source, because javac checks
     * access before this transformation runs; use {@link ShimFields}/
     * {@code ShimMethods} for that, which then need no {@code setAccessible}.)
     * <p>
     * This is a coarser, whole-class alternative to listing individual fields
     * in {@link #definalize()}.
     */
    boolean widenAccess() default false;
}
