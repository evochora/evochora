package org.evochora.compiler.diagnostics;

/**
 * Minimal compiler-internal logger with integer verbosity levels.
 * Levels: 0=ERROR, 1=WARN, 2=INFO, 3=DEBUG, 4=TRACE
 */
public final class CompilerLogger {

    public static final int ERROR = 0;
    public static final int WARN  = 1;
    public static final int INFO  = 2;
    public static final int DEBUG = 3;
    public static final int TRACE = 4;
    private static volatile int level = INFO;

	private CompilerLogger() {}

    public static void setLevel(int newLevel) { level = Math.max(ERROR, Math.min(TRACE, newLevel)); }

	public static void error(String msg) { if (level >= 0) System.err.println(prefix("ERROR") + msg); }
    public static void warn(String msg)  { if (level >= 1) System.out.println(prefix("WARN") + msg); }
    public static void info(String msg)  { if (level >= 2) System.out.println(prefix("INFO") + msg); }
	public static void debug(String msg) { if (level >= 3) System.out.println(prefix("DEBUG") + msg); }
	public static void trace(String msg) { if (level >= 4) System.out.println(prefix("TRACE") + msg); }

	private static String prefix(String lvl) { return "[COMPILER][" + lvl + "] "; }
}


