package org.evochora.junit.extensions.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class LogWatchExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback, AfterEachCallback, TestExecutionExceptionHandler {

    private static final String STORE_NAMESPACE = "org.evochora.junit.extensions.logging.LogWatchExtension";

    @Override
    public void beforeAll(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        ValidationRules rules = resolveRules(context);
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        TestScopedTurboFilter filter = new TestScopedTurboFilter(rules);
        filter.start();
        lc.addTurboFilter(filter);
        store.put("filter", filter);
        store.put("rules", rules);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // Don't create a new filter - just update the existing one with method-level rules
        ExtensionContext.Store store = context.getStore(ExtensionContext.Namespace.create(STORE_NAMESPACE));
        TestScopedTurboFilter filter = store.get("filter", TestScopedTurboFilter.class);
        if (filter != null) {
            ValidationRules methodRules = resolveRules(context);
            filter.updateRules(methodRules);
        }
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw throwable;
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
                throw new AssertionError(sb.toString());
            }

            // Clear events for next test but keep filter active
            filter.clearEvents();
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

    private String format(CapturedEvent e) {
        return String.format("[%s] %s - %s", e.level, e.loggerName, e.message);
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
        private volatile ValidationRules rules;

        TestScopedTurboFilter(ValidationRules rules) {
            this.rules = rules;
        }

        void updateRules(ValidationRules newRules) {
            this.rules = newRules;
        }

        @Override
        public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level, String format, Object[] params, Throwable t) {
            if (level.isGreaterOrEqual(toLogback(rules.minLevel))) {
                CapturedEvent ev = new CapturedEvent(logger.getName(), level, formatMessage(format, params));
                events.add(ev);
                if (isAllowedLocal(ev, rules.allows) || isExpectedLocal(ev, rules.expects)) {
                    return FilterReply.DENY;
                }
            }
            return FilterReply.NEUTRAL;
        }

        List<CapturedEvent> getCapturedEvents() {
            return new ArrayList<>(events);
        }

        void clearEvents() {
            events.clear();
        }

        private String formatMessage(String format, Object[] params) {
            return (format == null) ? "" : MessageFormatter.arrayFormat(format, params).getMessage();
        }

        private boolean isExpectedLocal(CapturedEvent e, ExpectLog[] expects) {
            for (ExpectLog exp : expects) {
                if (e.level.isGreaterOrEqual(toLogback(exp.level())) &&
                    Pattern.matches(exp.loggerPattern(), e.loggerName) &&
                    Pattern.matches(exp.messagePattern(), e.message)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isAllowedLocal(CapturedEvent e, AllowLog[] allows) {
            for (AllowLog a : allows) {
                if (e.level.isGreaterOrEqual(toLogback(a.level())) &&
                    Pattern.matches(a.loggerPattern(), e.loggerName) &&
                    Pattern.matches(a.messagePattern(), e.message)) {
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
            this.allows = allows;
            this.expects = expects;
        }
    }
}