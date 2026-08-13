package io.quarkiverse.shim.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** RUNTIME retention with one defaulted member, for the onConflict tests. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface KeepMarker {

    String value() default "";
}
