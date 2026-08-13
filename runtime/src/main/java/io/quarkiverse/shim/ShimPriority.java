package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the order in which multiple hooks woven into the same target method
 * run, when the order matters.
 * <p>
 * Lower values run first. {@code @ShimBefore} hooks execute in ascending
 * priority at method entry; {@code @ShimAfter} and {@code @ShimFinally} hooks
 * execute in ascending priority before each return, and {@code @ShimCatch} and
 * {@code @ShimFinally} hooks in ascending priority on the exceptional path. The
 * default priority is {@code 0}.
 * <p>
 * Hooks of equal priority run in the order the index reports them, which is not
 * source order and is not guaranteed across builds. Give them distinct
 * priorities whenever the relative order matters.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimPriority {

    int value();
}
