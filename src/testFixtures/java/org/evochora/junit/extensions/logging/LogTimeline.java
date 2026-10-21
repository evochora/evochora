package org.evochora.junit.extensions.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets {@link LogWatchExtension} keep a timeline of the log entries of every test of the class,
 * from the given level on, and print it when a test fails on its logs. The timeline carries, for
 * each entry, the milliseconds since the test began, the level, the logger, the message and the
 * thread, and whether the entry was logged during the teardown; the last {@link #limit()} entries
 * are kept.
 * <p>
 * The extension sees a log call before the logger's level filters it, so the timeline also shows
 * entries of loggers that the test logging configuration keeps off the console, such as the
 * stopping of a server. Every entry from the level on is formatted and kept, so a class that
 * logs heavily gets slower by it; the timeline is for classes whose failures only the order of
 * events can explain.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LogTimeline {

    /** The lowest level an entry needs to enter the timeline. */
    LogLevel level() default LogLevel.INFO;

    /** How many entries the timeline keeps; the oldest are dropped first. */
    int limit() default 2000;
}
