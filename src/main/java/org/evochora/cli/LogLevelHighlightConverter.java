package org.evochora.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * Logback converter for log level coloring in console output.
 *
 * <p>Colors:
 * <ul>
 *   <li>ERROR - Red</li>
 *   <li>WARN - Yellow</li>
 *   <li>INFO - Blue</li>
 *   <li>DEBUG/TRACE - Default</li>
 * </ul>
 */
public class LogLevelHighlightConverter extends CompositeConverter<ILoggingEvent> {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";

    @Override
    protected String transform(ILoggingEvent event, String in) {
        Level level = event.getLevel();
        return switch (level.toInt()) {
            case Level.ERROR_INT -> ANSI_RED + in + ANSI_RESET;
            case Level.WARN_INT -> ANSI_YELLOW + in + ANSI_RESET;
            case Level.INFO_INT -> ANSI_BLUE + in + ANSI_RESET;
            default -> in;
        };
    }
}
