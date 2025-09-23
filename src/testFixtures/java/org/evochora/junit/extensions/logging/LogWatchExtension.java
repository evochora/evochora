package org.evochora.junit.extensions.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * JUnit 5 extension capturing WARN/ERROR logs per test and failing on unexpected ones.
 */
public class LogWatchExtension implements BeforeEachCallback, AfterEachCallback, TestExecutionExceptionHandler {

    private static final String STORE_NAMESPACE = "org.evochora.junit.extensions.logging.LogWatchExtension";

    @Override
    public void beforeEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));

        // Resolve rules up-front so the filter can suppress expected/allowed logs from output
        ValidationRules rules = resolveRules(context);

        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        TestScopedTurboFilter filter = new TestScopedTurboFilter();
        filter.setRules(rules);
        filter.start();
        lc.addTurboFilter(filter);
        store.put("filter", filter);
        store.put("rules", rules);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        // Let the test fail normally; we still remove appender in afterEach
        throw throwable;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        TestScopedTurboFilter filter = store.remove("filter", TestScopedTurboFilter.class);

        if (filter != null) {
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            lc.getTurboFilterList().remove(filter);
            filter.stop();

            // Use same rules as used during execution
            ValidationRules rules = store.get("rules", ValidationRules.class);
            List<CapturedEvent> events = filter.getCapturedEvents();

            List<String> unexpected = findUnexpected(events, rules);
            List<String> missingExpected = findMissingExpected(events, rules);

            if (!unexpected.isEmpty() || !missingExpected.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (!unexpected.isEmpty()) {
                    sb.append("Unexpected WARN/ERROR logs:\n");
                    unexpected.forEach(msg -> sb.append("  ").append(msg).append('\n'));
                }
                if (!missingExpected.isEmpty()) {
                    sb.append("Missing expected logs:\n");
                    missingExpected.forEach(msg -> sb.append("  ").append(msg).append('\n'));
                }
                throw new AssertionError(sb.toString());
            }
        }
    }

    private ValidationRules resolveRules(ExtensionContext context) {
        FailOnLog fail = findFailOnLog(context);
        AllowLog[] allows = findAllowLogs(context);
        ExpectLog[] expects = findExpectLogs(context);

        LogLevel minLevel = (fail != null) ? fail.level() : LogLevel.WARN;
        boolean disabled = (fail != null) && fail.disabled();
        return new ValidationRules(minLevel, disabled, allows, expects);
    }

    private FailOnLog findFailOnLog(ExtensionContext context) {
        FailOnLog fromElement = context.getElement().map(el -> el.getAnnotation(FailOnLog.class)).orElse(null);
        if (fromElement != null) {
            return fromElement;
        }
        return context.getTestClass().map(c -> c.getAnnotation(FailOnLog.class)).orElse(null);
    }

    private AllowLog[] findAllowLogs(ExtensionContext context) {
        AllowLog[] fromElement = context.getElement().map(el -> el.getAnnotationsByType(AllowLog.class)).orElse(new AllowLog[0]);
        AllowLog[] fromClass = context.getTestClass().map(c -> c.getAnnotationsByType(AllowLog.class)).orElse(new AllowLog[0]);
        if (fromElement.length == 0 && fromClass.length == 0) return new AllowLog[0];
        AllowLog[] merged = new AllowLog[fromElement.length + fromClass.length];
        System.arraycopy(fromClass, 0, merged, 0, fromClass.length);
        System.arraycopy(fromElement, 0, merged, fromClass.length, fromElement.length);
        return merged;
    }

    private ExpectLog[] findExpectLogs(ExtensionContext context) {
        ExpectLog[] fromElement = context.getElement().map(el -> el.getAnnotationsByType(ExpectLog.class)).orElse(new ExpectLog[0]);
        ExpectLog[] fromClass = context.getTestClass().map(c -> c.getAnnotationsByType(ExpectLog.class)).orElse(new ExpectLog[0]);
        if (fromElement.length == 0 && fromClass.length == 0) return new ExpectLog[0];
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
            int count = 0;
            Pattern lp = Pattern.compile(exp.loggerPattern());
            Pattern mp = Pattern.compile(exp.messagePattern());
            Level lvl = toLogback(exp.level());
            for (CapturedEvent e : events) {
                if (e.level.isGreaterOrEqual(lvl) && lp.matcher(e.loggerName).matches() && mp.matcher(e.message).matches()) {
                    count++;
                }
            }
            if (count < exp.occurrences()) {
                result.add("Expected " + exp.occurrences() + " x [" + exp.level() + "] logger=\"" + exp.loggerPattern() + "\" message=\"" + exp.messagePattern() + "\"");
            }
        }
        return result;
    }

    private boolean isAllowed(CapturedEvent e, AllowLog[] allows) {
        for (AllowLog a : allows) {
            Level lvl = toLogback(a.level());
            if (!e.level.isGreaterOrEqual(lvl)) continue;
            if (!Pattern.matches(a.loggerPattern(), e.loggerName)) continue;
            if (!Pattern.matches(a.messagePattern(), e.message)) continue;
            return true; // simple allow-any occurrence; stricter counts are enforced via ExpectLog
        }
        return false;
    }

    private boolean isExpected(CapturedEvent e, ExpectLog[] expects) {
        for (ExpectLog exp : expects) {
            Level lvl = toLogback(exp.level());
            if (!e.level.isGreaterOrEqual(lvl)) continue;
            if (!Pattern.matches(exp.loggerPattern(), e.loggerName)) continue;
            if (!Pattern.matches(exp.messagePattern(), e.message)) continue;
            return true;
        }
        return false;
    }

    private boolean isAtLeast(Level level, LogLevel minLevel) {
        return switch (minLevel) {
            case WARN -> level.isGreaterOrEqual(Level.WARN);
            case ERROR -> level.isGreaterOrEqual(Level.ERROR);
        };
    }

    private String format(CapturedEvent e) {
        return "[" + e.level + "] " + e.loggerName + " - " + e.message;
    }

    private Level toLogback(LogLevel lvl) {
        return (lvl == LogLevel.ERROR) ? Level.ERROR : Level.WARN;
    }

    private static class TestScopedTurboFilter extends TurboFilter {
        private final List<CapturedEvent> events = new CopyOnWriteArrayList<>();
        private volatile ValidationRules rules;

        void setRules(ValidationRules rules) { this.rules = rules; }

        @Override
        public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level, String format, Object[] params, Throwable t) {
            if (level.isGreaterOrEqual(Level.WARN)) {
                String message = formatMessage(format, params);
                CapturedEvent ev = new CapturedEvent(logger.getName(), level, message);
                events.add(ev);
                // Suppress only expected/allowed logs from console output
                if (rules != null) {
                    boolean isAllowed = isAllowedLocal(ev, rules.allows);
                    boolean isExpected = isExpectedLocal(ev, rules.expects);
                    if (isAllowed || isExpected) {
                        return FilterReply.DENY; // prevent propagation to appenders
                    }
                }
            }
            return FilterReply.NEUTRAL;
        }

        List<CapturedEvent> getCapturedEvents() {
            return new ArrayList<>(events);
        }

        private String formatMessage(String format, Object[] params) {
            try {
                if (format == null) return "";
                if (params == null || params.length == 0) return format;
                return MessageFormatter.arrayFormat(format, params).getMessage();
            } catch (Throwable ignored) {
                return String.valueOf(format);
            }
        }

        private boolean isExpectedLocal(CapturedEvent e, ExpectLog[] expects) {
            if (expects == null) return false;
            for (ExpectLog exp : expects) {
                Level lvl = toLogbackLocal(exp.level());
                if (!e.level.isGreaterOrEqual(lvl)) continue;
                if (!Pattern.matches(exp.loggerPattern(), e.loggerName)) continue;
                if (!Pattern.matches(exp.messagePattern(), e.message)) continue;
                return true;
            }
            return false;
        }

        private boolean isAllowedLocal(CapturedEvent e, AllowLog[] allows) {
            if (allows == null) return false;
            for (AllowLog a : allows) {
                Level lvl = toLogbackLocal(a.level());
                if (!e.level.isGreaterOrEqual(lvl)) continue;
                if (!Pattern.matches(a.loggerPattern(), e.loggerName)) continue;
                if (!Pattern.matches(a.messagePattern(), e.message)) continue;
                return true;
            }
            return false;
        }

        private Level toLogbackLocal(LogLevel lvl) {
            return (lvl == LogLevel.ERROR) ? Level.ERROR : Level.WARN;
        }
    }

    private static class CapturedEvent {
        final String loggerName;
        final Level level;
        final String message;

        CapturedEvent(String loggerName, Level level, String message) {
            this.loggerName = loggerName;
            this.level = level;
            this.message = message != null ? message : "";
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
            this.allows = allows != null ? allows : new AllowLog[0];
            this.expects = expects != null ? expects : new ExpectLog[0];
        }
    }
}


