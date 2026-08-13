package io.quarkiverse.shim.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** CLASS retention: written to the class file, invisible to reflection. */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface ClassRetainedAnnotation {

    String value() default "";
}
