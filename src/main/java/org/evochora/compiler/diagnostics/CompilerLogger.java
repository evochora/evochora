package org.evochora.compiler.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal compiler-internal logger with integer verbosity levels.
 * Levels: 0=ERROR, 1=WARN, 2=INFO, 3=DEBUG, 4=TRACE
 * Now uses SLF4J to avoid interfering with TUI output.
 */
public final class CompilerLogger {

    public static final int ERROR = 0;
    public static final int WARN  = 1;
    public static final int INFO  = 2;
    public static final int DEBUG = 3;
    public static final int TRACE = 4;
    private static volatile int level = INFO;
    
    // Use SLF4J logger instead of System.out/err
    private static final Logger logger = LoggerFactory.getLogger(CompilerLogger.class);

	private CompilerLogger() {}

    public static void setLevel(int newLevel) { level = Math.max(ERROR, Math.min(TRACE, newLevel)); }

	public static void error(String msg) { 
        if (level >= ERROR) logger.error(msg); 
    }
    
    public static void warn(String msg)  { 
        if (level >= WARN) logger.warn(msg);  
    }
    
    public static void info(String msg)  { 
        if (level >= INFO) logger.info(msg);  
    }
    
	public static void debug(String msg) { 
        if (level >= DEBUG) logger.debug(msg); 
    }
    
	public static void trace(String msg) { 
        if (level >= TRACE) logger.trace(msg); 
    }
}


