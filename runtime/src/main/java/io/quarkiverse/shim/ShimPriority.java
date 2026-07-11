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
 * priority at method entry; {@code @ShimAfter} hooks execute in ascending
 * priority before each return. Hooks with equal priority run in an unspecified
 * but stable order. The default priority is {@code 0}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ShimPriority {

    int value();
}
