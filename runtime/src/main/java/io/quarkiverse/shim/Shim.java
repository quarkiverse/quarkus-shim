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
 * A patch for a third-party bug should normally be pinned to the releases it
 * was written against with {@link #versions()}: once the dependency is upgraded
 * past them the shim stops being applied instead of silently patching code that
 * has changed underneath it.
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
     * configuration: {@code quarkus.shim.instances."<name>".enabled=false}
     * disables just this shim class while leaving the rest active. Defaults to
     * the simple name of the shim class.
     */
    String name() default "";

    /**
     * Maven coordinate {@code "groupId:artifactId"} of the dependency this shim
     * patches. Combined with {@link #versions()} it pins the patch to the
     * releases it was written for.
     * <p>
     * When left blank and {@link #versions()} is set, the artifact that
     * contains the target class is used, which is what you want for the common
     * case of patching a class in a third-party library.
     * <p>
     * Set on its own (without {@link #versions()}) it becomes a presence gate:
     * the shim applies only while that dependency is on the classpath.
     */
    String dependency() default "";

    /**
     * The versions of {@link #dependency()} this shim applies to, as a Maven
     * version range — for example {@code "[1.2,1.5)"} (1.2 up to but excluding
     * 1.5), {@code "(,2.0)"} (anything below 2.0), {@code "[1.4.2]"} or
     * {@code "1.4.2"} (that version exactly), or a union such as
     * {@code "[1.2,1.3],[1.5,1.6]"}.
     * <p>
     * When the resolved version falls outside the range the target class is
     * left untouched (see {@link #onVersionMismatch()}), so upgrading the
     * dependency past the patched releases retires the shim instead of weaving
     * a stale patch into code that has moved on.
     * <p>
     * Note that a bare version means <em>exactly</em> that version here, unlike
     * a Maven dependency declaration where it is only a preference.
     */
    String versions() default "";

    /**
     * What to do when the dependency is missing or its version falls outside
     * {@link #versions()}. Defaults to {@link VersionMismatch#SKIP}: the shim
     * is quietly retired with a warning.
     */
    VersionMismatch onVersionMismatch() default VersionMismatch.SKIP;

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
