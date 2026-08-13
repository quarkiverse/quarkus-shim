package io.quarkiverse.shim;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Copies the other annotations declared on this shim element to an element of
 * the class targeted by {@link Shim}.
 * <p>
 * On a shim class, the annotations are copied to the target class. On a shim
 * field or method, {@link #target()} selects the target member and defaults to
 * the shim member's own name. Methods may select an overload with either
 * {@link #paramTypes()} or {@link #descriptor()}.
 * <p>
 * Annotation member values and retention visibility are preserved. Shim
 * control annotations ({@code @Shim}, hook annotations, this annotation, and
 * {@code @ShimPriority}) are never copied. When the target already declares
 * the same annotation type, {@link #onConflict()} controls whether it is
 * replaced, retained, or treated as an augmentation error.
 * <p>
 * The annotations are attached by bytecode transformation, and their retention
 * is preserved. A {@code RUNTIME} annotation is therefore visible to reflection
 * on the patched class; a {@code CLASS} annotation is written to the class file
 * but, as always, is not visible to reflection. {@code SOURCE} annotations never
 * reach the class file at all and so cannot be copied.
 * <p>
 * Either way the annotation is attached after the Jandex index was built, so
 * build steps that read only the index — CDI scopes, REST endpoints, and most
 * Quarkus extensions — will not see it.
 * <p>
 * An unqualified {@link #target()} matches every method of that name. Compiler
 * generated bridge methods are skipped, so a covariant override is annotated
 * once; pin an overload with {@link #paramTypes()} when a class has several.
 */
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.CLASS)
public @interface ShimAnnotate {

    /**
     * Target method or field name. Defaults to the annotated shim member's
     * name. Must be left empty when this annotation is placed on a shim class.
     */
    String target() default "";

    /**
     * Full JVM method descriptor used to select one overload, for example
     * {@code "(Ljava/lang/String;)V"}.
     */
    String descriptor() default "";

    /**
     * Target method parameter types used to select one overload. This is a
     * readable alternative to {@link #descriptor()}.
     */
    Class<?>[] paramTypes() default {};

    /**
     * Controls what happens when the target element already declares an
     * annotation of the same type. Replacement is the default because an
     * explicitly supplied shim annotation is expected to patch the target.
     */
    AnnotationConflict onConflict() default AnnotationConflict.REPLACE;
}
