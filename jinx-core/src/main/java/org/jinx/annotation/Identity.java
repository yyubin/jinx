package org.jinx.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Identity {
    /** Starting value (default 1) */
    long start() default 1;

    /** Increment value (default 1) */
    int increment() default 1;

    /** Cache size for DBs like Oracle/DB2 (0 = DB default) */
    int cache() default 0;

    /** Minimum value support */
    long min() default Long.MIN_VALUE;

    /** Maximum value support */
    long max() default Long.MAX_VALUE;

    /** Additional dialect-specific options as key-value pairs */
    String[] options() default {};
}
