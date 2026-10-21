package org.evochora.junit.extensions.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class LogWatchExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback,
        AfterTestExecutionCallback, AfterEachCallback, TestExecutionExceptionHandler {

    private static final String STORE_NAMESPACE = "org.evochora.junit.extensions.logging.LogWatchExtension";

    @Override
    public void beforeAll(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        ValidationRules rules = resolveRules(context);
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        TestScopedTurboFilter filter = new TestScopedTurboFilter(rules);
        context.getTestClass().map(c -> c.getAnnotation(LogTimeline.class))
                .ifPresent(timeline -> filter.recordTimeline(timeline.level(), timeline.limit()));
        filter.start();
        lc.addTurboFilter(filter);
        store.put("filter", filter);
        store.put("rules", rules);
        // Events are already empty in a new filter, but clearEvents() ensures clean state
        filter.clearEvents();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // Clear events from previous test FIRST to ensure clean slate
        // Then update rules for current test method
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        TestScopedTurboFilter filter = store.get("filter", TestScopedTurboFilter.class);
        if (filter != null) {
            // CRITICAL: Clear events BEFORE updating rules to ensure proper isolation
            filter.clearEvents();
            filter.testStarted();
            
            // Now update rules for the new test method
            ValidationRules methodRules = resolveRules(context);
            filter.updateRules(methodRules);
        }
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw throwable;
    }

    /**
     * Runs right after the test method, before its {@code @AfterEach} methods: from here on an
     * event belongs to the teardown, which the report says, so that a log written while a server
     * or a service is stopped is told apart from one written while the test ran.
     */
    @Override
    public void afterTestExecution(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        TestScopedTurboFilter filter = store.get("filter", TestScopedTurboFilter.class);
        if (filter != null) {
            filter.testMethodEnded();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        TestScopedTurboFilter filter = store.remove("filter", TestScopedTurboFilter.class);

        if (filter != null) {
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            lc.getTurboFilterList().remove(filter);
            filter.stop();
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        TestScopedTurboFilter filter = store.get("filter", TestScopedTurboFilter.class);

        if (filter != null) {
            // Resolve rules for the current test method (not class-level rules)
            ValidationRules rules = resolveRules(context);
            List<CapturedEvent> events = filter.getCapturedEvents();

            List<String> unexpected = findUnexpected(events, rules);
            List<String> missingExpected = findMissingExpected(events, rules);

            if (!unexpected.isEmpty() || !missingExpected.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (!unexpected.isEmpty()) {
                    sb.append("Unexpected logs:\n");
                    unexpected.forEach(msg -> sb.append("  ").append(msg).append('\n'));
                }
                if (!missingExpected.isEmpty()) {
                    sb.append("Missing expected logs:\n");
                    missingExpected.forEach(msg -> sb.append("  ").append(msg).append('\n'));
                }
                List<String> timeline = filter.getTimeline();
                if (!timeline.isEmpty()) {
                    sb.append("Log timeline of the test, every entry from level ")
                            .append(filter.timelineLevel()).append(" on:\n");
                    timeline.forEach(line -> sb.append("  ").append(line).append('\n'));
                }
                throw new AssertionError(sb.toString());
            }

            // NOTE: Events are NOT cleared here - they will be cleared in beforeEach() of the next test
            // This ensures proper isolation and prevents race conditions between tests
        }
    }

    private ValidationRules resolveRules(ExtensionContext context) {
        FailOnLog fail = context.getElement().map(el -> el.getAnnotation(FailOnLog.class))
                .orElse(context.getTestClass().map(c -> c.getAnnotation(FailOnLog.class)).orElse(null));
        AllowLog[] allows = findAllowLogs(context);
        ExpectLog[] expects = findExpectLogs(context);

        LogLevel minLevel = (fail != null) ? fail.level() : LogLevel.WARN;
        boolean disabled = (fail != null) && fail.disabled();
        return new ValidationRules(minLevel, disabled, allows, expects);
    }

    private AllowLog[] findAllowLogs(ExtensionContext context) {
        AllowLog[] fromElement = context.getElement().map(el -> el.getAnnotationsByType(AllowLog.class)).orElse(new AllowLog[0]);
        AllowLog[] fromClass = context.getTestClass().map(c -> c.getAnnotationsByType(AllowLog.class)).orElse(new AllowLog[0]);
        AllowLog[] merged = new AllowLog[fromElement.length + fromClass.length];
        System.arraycopy(fromClass, 0, merged, 0, fromClass.length);
        System.arraycopy(fromElement, 0, merged, fromClass.length, fromElement.length);
        return merged;
    }

    private ExpectLog[] findExpectLogs(ExtensionContext context) {
        ExpectLog[] fromElement = context.getElement().map(el -> el.getAnnotationsByType(ExpectLog.class)).orElse(new ExpectLog[0]);
        ExpectLog[] fromClass = context.getTestClass().map(c -> c.getAnnotationsByType(ExpectLog.class)).orElse(new ExpectLog[0]);
        ExpectLog[] merged = new ExpectLog[fromElement.length + fromClass.length];
        System.arraycopy(fromClass, 0, merged, 0, fromClass.length);
        System.arraycopy(fromElement, 0, merged, fromClass.length, fromElement.length);
        return merged;
    }

    private List<String> findUnexpected(List<CapturedEvent> events, ValidationRules rules) {
        if (rules.disabled) return List.of();
        List<String> result = new ArrayList<>();
        for (CapturedEvent e : events) {
            if (!isAtLeast(e.level, rules.minLevel)) continue;
            if (isAllowed(e, rules.allows)) continue;
            if (isExpected(e, rules.expects)) continue;
            result.add(format(e));
        }
        return result;
    }

    private List<String> findMissingExpected(List<CapturedEvent> events, ValidationRules rules) {
        List<String> result = new ArrayList<>();
        for (ExpectLog exp : rules.expects) {
            long count = events.stream().filter(e -> matches(e, exp)).count();
            if (count < exp.occurrences()) {
                result.add(String.format("Expected %d x [%s] logger=\"%s\" message=\"%s\", but found %d.",
                        exp.occurrences(), exp.level(), exp.loggerPattern(), exp.messagePattern(), count));
            }
        }
        return result;
    }

    private boolean matches(CapturedEvent e, ExpectLog exp) {
        return e.level.isGreaterOrEqual(toLogback(exp.level())) &&
               Pattern.matches(exp.loggerPattern(), e.loggerName) &&
               Pattern.matches(exp.messagePattern(), e.message);
    }

    private boolean isAllowed(CapturedEvent e, AllowLog[] allows) {
        for (AllowLog a : allows) {
            if (e.level.isGreaterOrEqual(toLogback(a.level())) &&
                Pattern.matches(a.loggerPattern(), e.loggerName) &&
                Pattern.matches(a.messagePattern(), e.message)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExpected(CapturedEvent e, ExpectLog[] expects) {
        for (ExpectLog exp : expects) {
            if (matches(e, exp)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAtLeast(Level level, LogLevel minLevel) {
        return level.isGreaterOrEqual(toLogback(minLevel));
    }

    /**
     * One unexpected event for the report: the line as logged, where and when it was logged, what
     * was thrown with it, by class and causes first and as a stack trace below, then the stack of
     * the thread that logged it and the stacks of the other threads of the system at that moment,
     * so that a failure that only the log shows can be traced to the code that produced it and to
     * what the rest of the system was doing.
     */
    private String format(CapturedEvent e) {
        String line = String.format("[%s] %s - %s", e.level, e.loggerName, e.message);
        String where = String.format("    (thread %s, %d ms after the test began, %s)",
                e.thread, e.millisSinceTestStart, e.duringTeardown ? "during the teardown" : "while the test ran");
        StringBuilder report = new StringBuilder(line);
        if (e.throwable.isEmpty()) {
            report.append('\n').append(where);
        } else {
            report.append("\n    ").append(e.throwable).append('\n').append(where).append('\n').append(e.stackTrace);
        }
        if (!e.loggingStack.isEmpty()) {
            report.append("\n    logged from:\n").append(e.loggingStack);
        }
        if (!e.threadDump.isEmpty()) {
            report.append("\n    other threads at that moment:\n").append(e.threadDump);
        }
        return report.toString();
    }

    private static Level toLogback(LogLevel lvl) {
        return switch (lvl) {
            case INFO -> Level.INFO;
            case WARN -> Level.WARN;
            case ERROR -> Level.ERROR;
        };
    }

    private static class TestScopedTurboFilter extends TurboFilter {
        private final List<CapturedEvent> events = new CopyOnWriteArrayList<>();
        /** The timeline of the test, oldest entry first; guarded by itself. */
        private final Deque<String> timeline = new ArrayDeque<>();
        private volatile long testStartNanos = System.nanoTime();
        private volatile boolean testMethodEnded;
        private volatile ValidationRules rules;
        /** The lowest level an entry needs to enter the timeline, {@code null} when none is kept. */
        private volatile LogLevel timelineLevel;
        private int timelineLimit;
        private int timelineDropped;

        TestScopedTurboFilter(ValidationRules rules) {
            this.rules = rules;
        }

        void updateRules(ValidationRules newRules) {
            this.rules = newRules;
        }

        /**
         * Sees every log call of the test before the logger's level filters it. A call with no
         * message is a logger asking whether a level is enabled; it enters no timeline.
         */
        @Override
        public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level, String format, Object[] params, Throwable t) {
            long millis = (System.nanoTime() - testStartNanos) / 1_000_000L;
            boolean teardown = testMethodEnded;
            String thread = Thread.currentThread().getName();
            LogLevel kept = timelineLevel;
            if (kept != null && format != null && level.isGreaterOrEqual(toLogback(kept))) {
                recordInTimeline(String.format("%6d ms [%s] %s - %s (thread %s%s)", millis, level, logger.getName(),
                        formatMessage(format, params), thread, teardown ? ", teardown" : ""));
            }
            if (level.isGreaterOrEqual(toLogback(rules.minLevel))) {
                Throwable thrown = t != null ? t
                        : (format == null ? null : MessageFormatter.arrayFormat(format, params).getThrowable());
                String message = formatMessage(format, params);
                boolean allowed = isAllowedLocal(logger.getName(), level, message, rules.allows);
                boolean expected = isExpectedLocal(logger.getName(), level, message, rules.expects);
                boolean reported = !rules.disabled && !allowed && !expected;
                CapturedEvent ev = new CapturedEvent(logger.getName(), level, message,
                        describe(thrown), stackTraceOf(thrown), thread, millis, teardown,
                        reported ? loggingStack() : "", reported ? otherThreads() : "");
                events.add(ev);
                if (allowed || expected) {
                    return FilterReply.DENY;
                }
            }
            return FilterReply.NEUTRAL;
        }

        void recordTimeline(LogLevel level, int limit) {
            this.timelineLevel = level;
            this.timelineLimit = limit;
        }

        LogLevel timelineLevel() {
            return timelineLevel;
        }

        private void recordInTimeline(String line) {
            synchronized (timeline) {
                if (timeline.size() >= timelineLimit) {
                    timeline.removeFirst();
                    timelineDropped++;
                }
                timeline.addLast(line);
            }
        }

        /** The timeline of the test, oldest entry first, headed by a count of dropped entries if any. */
        List<String> getTimeline() {
            synchronized (timeline) {
                List<String> lines = new ArrayList<>(timeline);
                if (timelineDropped > 0) {
                    lines.add(0, "... " + timelineDropped + " earlier entries dropped");
                }
                return lines;
            }
        }

        /**
         * The stack of the thread logging an event, taken while it logs, without the frames of the
         * logging libraries and of this extension: it shows where the event was logged, which for a
         * throwable is where it was caught, not where it was created.
         */
        private static String loggingStack() {
            StringBuilder sb = new StringBuilder();
            int frames = 0;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String cls = frame.getClassName();
                if (cls.startsWith("java.lang.Thread") || cls.startsWith("ch.qos.logback")
                        || cls.startsWith("org.slf4j") || cls.startsWith(LogWatchExtension.class.getName())) {
                    continue;
                }
                sb.append("        at ").append(frame).append('\n');
                if (++frames == 40) {
                    sb.append("        ...\n");
                    break;
                }
            }
            return sb.toString().stripTrailing();
        }

        /**
         * The stacks of the other threads at the moment an event is logged, limited to threads that
         * run code of the system, Jetty or Javalin, and to the top of each stack, so that the report
         * shows what the rest of the system was doing, for example a test stopping a server while
         * the server still answers.
         */
        private static String otherThreads() {
            StringBuilder sb = new StringBuilder();
            Thread self = Thread.currentThread();
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                StackTraceElement[] frames = entry.getValue();
                if (thread == self || !runsTheSystem(frames)) {
                    continue;
                }
                sb.append("        \"").append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
                for (int i = 0; i < frames.length && i < 30; i++) {
                    sb.append("            at ").append(frames[i]).append('\n');
                }
                if (frames.length > 30) {
                    sb.append("            ...\n");
                }
                if (sb.length() > 20000) {
                    sb.append("        ... (cut)\n");
                    break;
                }
            }
            return sb.toString().stripTrailing();
        }

        private static boolean runsTheSystem(StackTraceElement[] frames) {
            for (StackTraceElement frame : frames) {
                String cls = frame.getClassName();
                if (cls.startsWith("org.evochora.") || cls.startsWith("org.eclipse.jetty.") || cls.startsWith("io.javalin.")) {
                    return true;
                }
            }
            return false;
        }

        List<CapturedEvent> getCapturedEvents() {
            return new ArrayList<>(events);
        }

        void testStarted() {
            testStartNanos = System.nanoTime();
            testMethodEnded = false;
        }

        void testMethodEnded() {
            testMethodEnded = true;
        }

        void clearEvents() {
            events.clear();
            synchronized (timeline) {
                timeline.clear();
                timelineDropped = 0;
            }
        }

        /**
         * Describes a logged throwable by the class and message of it and of each of its causes, so
         * that a test failing on an unexpected log shows what was thrown.
         *
         * @param thrown the throwable logged with the event, or {@code null}
         * @return the description, empty if nothing was thrown
         */
        private static String describe(Throwable thrown) {
            StringBuilder sb = new StringBuilder();
            for (Throwable c = thrown; c != null && sb.length() < 2000; c = c.getCause() == c ? null : c.getCause()) {
                if (sb.length() > 0) {
                    sb.append(" <- caused by ");
                }
                sb.append(c.getClass().getName());
                if (c.getMessage() != null) {
                    sb.append(": ").append(c.getMessage());
                }
            }
            return sb.toString();
        }

        /**
         * The stack trace of what was thrown, with its causes, as the JDK prints it, every line
         * indented for the report, cut after a bound so that a deep trace cannot flood it.
         */
        private static String stackTraceOf(Throwable thrown) {
            if (thrown == null) {
                return "";
            }
            java.io.StringWriter out = new java.io.StringWriter();
            thrown.printStackTrace(new java.io.PrintWriter(out));
            String trace = out.toString().stripTrailing();
            if (trace.length() > 12000) {
                trace = trace.substring(0, 12000) + "\n... (cut)";
            }
            return trace.indent(8).stripTrailing();
        }

        private String formatMessage(String format, Object[] params) {
            return (format == null) ? "" : MessageFormatter.arrayFormat(format, params).getMessage();
        }

        private static boolean isExpectedLocal(String loggerName, Level level, String message, ExpectLog[] expects) {
            for (ExpectLog exp : expects) {
                if (level.isGreaterOrEqual(toLogback(exp.level())) &&
                    Pattern.matches(exp.loggerPattern(), loggerName) &&
                    Pattern.matches(exp.messagePattern(), message)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isAllowedLocal(String loggerName, Level level, String message, AllowLog[] allows) {
            for (AllowLog a : allows) {
                if (level.isGreaterOrEqual(toLogback(a.level())) &&
                    Pattern.matches(a.loggerPattern(), loggerName) &&
                    Pattern.matches(a.messagePattern(), message)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class CapturedEvent {
        final String loggerName;
        final Level level;
        final String message;
        /** What was thrown with the event, by class and message along its causes; empty if nothing. */
        final String throwable;
        /** The stack trace of what was thrown, with its causes, indented for the report; empty if nothing. */
        final String stackTrace;
        /** The thread the event was logged on. */
        final String thread;
        /** How long after the start of the test the event was logged. */
        final long millisSinceTestStart;
        /** Whether the event was logged after the test method had ended, in its teardown. */
        final boolean duringTeardown;
        /** The stack of the logging thread when it logged, for an event the report will show; else empty. */
        final String loggingStack;
        /** The stacks of the other threads when the event was logged, for an event the report will show; else empty. */
        final String threadDump;

        CapturedEvent(String loggerName, Level level, String message, String throwable, String stackTrace,
                      String thread, long millisSinceTestStart, boolean duringTeardown,
                      String loggingStack, String threadDump) {
            this.loggerName = loggerName;
            this.level = level;
            this.message = message != null ? message : "";
            this.throwable = throwable;
            this.stackTrace = stackTrace;
            this.thread = thread;
            this.millisSinceTestStart = millisSinceTestStart;
            this.duringTeardown = duringTeardown;
            this.loggingStack = loggingStack;
            this.threadDump = threadDump;
        }
    }

    private static class ValidationRules {
        final LogLevel minLevel;
        final boolean disabled;
        final AllowLog[] allows;
        final ExpectLog[] expects;

        ValidationRules(LogLevel minLevel, boolean disabled, AllowLog[] allows, ExpectLog[] expects) {
            this.minLevel = minLevel;
            this.disabled = disabled;
            this.allows = allows;
            this.expects = expects;
        }
    }
}