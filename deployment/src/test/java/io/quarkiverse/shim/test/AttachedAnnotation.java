package io.quarkiverse.shim.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface AttachedAnnotation {

    String value();

    int number();

    Class<?> type();

    Level level();

    Nested nested();

    String[] tags();

    enum Level {
        LOW,
        HIGH
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Nested {
        String value();
    }
}
