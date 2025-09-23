package org.evochora.junit.extensions.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(AllowLogs.class)
public @interface AllowLog {
    LogLevel level();
    String loggerPattern() default ".*";
    String messagePattern() default ".*";
    int occurrences() default Integer.MAX_VALUE;
}


