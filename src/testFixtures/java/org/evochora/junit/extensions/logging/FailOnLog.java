package org.evochora.junit.extensions.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls global failing behavior on WARN/ERROR logs during a test.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FailOnLog {

    LogLevel level() default LogLevel.WARN;

    boolean disabled() default false;
}


