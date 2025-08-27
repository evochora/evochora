package org.evochora.compiler.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal compiler-internal logger with integer verbosity levels.
 * Levels: 0=ERROR, 1=WARN, 2=INFO, 3=DEBUG, 4=TRACE
 * Now uses SLF4J to avoid interfering with TUI output.
 */
public final class CompilerLogger {

    /** Log level for errors. */
    public static final int ERROR = 0;
    /** Log level for warnings. */
    public static final int WARN  = 1;
    /** Log level for informational messages. */
    public static final int INFO  = 2;
    /** Log level for debug messages. */
    public static final int DEBUG = 3;
    /** Log level for trace messages. */
    public static final int TRACE = 4;
    private static volatile int level = INFO;
    
    // Use SLF4J logger instead of System.out/err
    private static final Logger logger = LoggerFactory.getLogger(CompilerLogger.class);

	private CompilerLogger() {}

    /**
     * Sets the logging verbosity level.
     * @param newLevel The new level to set.
     */
    public static void setLevel(int newLevel) { level = Math.max(ERROR, Math.min(TRACE, newLevel)); }

    /**
     * Logs an error message.
     * @param msg The message to log.
     */
	public static void error(String msg) { 
        if (level >= ERROR) logger.error(msg); 
    }
    
    /**
     * Logs a warning message.
     * @param msg The message to log.
     */
    public static void warn(String msg)  { 
        if (level >= WARN) logger.warn(msg);  
    }
    
    /**
     * Logs an informational message.
     * @param msg The message to log.
     */
    public static void info(String msg)  { 
        if (level >= INFO) logger.info(msg);  
    }
    
    /**
     * Logs a debug message.
     * @param msg The message to log.
     */
	public static void debug(String msg) { 
        if (level >= DEBUG) logger.debug(msg); 
    }
    
    /**
     * Logs a trace message.
     * @param msg The message to log.
     */
	public static void trace(String msg) { 
        if (level >= TRACE) logger.trace(msg); 
    }
}


