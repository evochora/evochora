package org.evochora.testutils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility to monitor log output during tests and detect warnings/errors.
 */
public class LogMonitor {
    
    private final ByteArrayOutputStream logOutput;
    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final PrintStream capturedOut;
    private final PrintStream capturedErr;
    
    private final Set<String> expectedWarnings = new HashSet<>();
    private final Set<String> expectedErrors = new HashSet<>();
    
    private static final Pattern WARN_PATTERN = Pattern.compile(".*\\[WARN\\].*|.*WARN.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_PATTERN = Pattern.compile(".*\\[ERROR\\].*|.*ERROR.*", Pattern.CASE_INSENSITIVE);
    
    public LogMonitor() {
        this.logOutput = new ByteArrayOutputStream();
        this.originalOut = System.out;
        this.originalErr = System.err;
        this.capturedOut = new PrintStream(logOutput);
        this.capturedErr = new PrintStream(logOutput);
    }
    
    public void startMonitoring() {
        System.setOut(capturedOut);
        System.setErr(capturedErr);
    }
    
    public void stopMonitoring() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }
    
    public List<String> getWarnings() {
        return getLogLines().stream()
                .filter(line -> WARN_PATTERN.matcher(line).matches())
                .toList();
    }
    
    public List<String> getErrors() {
        return getLogLines().stream()
                .filter(line -> ERROR_PATTERN.matcher(line).matches())
                .toList();
    }
    
    public List<String> getWarningsAndErrors() {
        List<String> result = new ArrayList<>();
        result.addAll(getWarnings());
        result.addAll(getErrors());
        return result;
    }
    
    public boolean hasWarningsOrErrors() {
        return !getWarningsAndErrors().isEmpty();
    }
    
    /**
     * Checks if any unexpected WARN or ERROR events were captured.
     */
    public boolean hasUnexpectedWarningsOrErrors() {
        List<String> warnings = getWarnings();
        List<String> errors = getErrors();
        
        // Check for unexpected warnings (partial match)
        for (String warning : warnings) {
            boolean found = false;
            for (String expected : expectedWarnings) {
                if (warning.contains(expected)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return true;
            }
        }
        
        // Check for unexpected errors (partial match)
        for (String error : errors) {
            boolean found = false;
            for (String expected : expectedErrors) {
                if (error.contains(expected)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Gets unexpected warnings and errors for debugging.
     */
    public String getUnexpectedWarningsAndErrorsSummary() {
        List<String> warnings = getWarnings();
        List<String> errors = getErrors();
        
        List<String> unexpectedWarnings = new ArrayList<>();
        List<String> unexpectedErrors = new ArrayList<>();
        
        for (String warning : warnings) {
            if (!expectedWarnings.contains(warning)) {
                unexpectedWarnings.add(warning);
            }
        }
        
        for (String error : errors) {
            if (!expectedErrors.contains(error)) {
                unexpectedErrors.add(error);
            }
        }
        
        if (unexpectedWarnings.isEmpty() && unexpectedErrors.isEmpty()) {
            return "No unexpected warnings or errors logged";
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("Unexpected warnings and errors logged:\n");
        if (!unexpectedWarnings.isEmpty()) {
            summary.append("WARNINGS:\n");
            unexpectedWarnings.forEach(w -> summary.append("  ").append(w).append("\n"));
        }
        if (!unexpectedErrors.isEmpty()) {
            summary.append("ERRORS:\n");
            unexpectedErrors.forEach(e -> summary.append("  ").append(e).append("\n"));
        }
        return summary.toString();
    }
    
    public String getLogOutput() {
        return logOutput.toString();
    }
    
    private List<String> getLogLines() {
        String output = logOutput.toString();
        if (output.isEmpty()) {
            return List.of();
        }
        return List.of(output.split("\\r?\\n"));
    }
    
    public void clear() {
        logOutput.reset();
    }
    
    /**
     * Marks a warning message as expected (will not cause test failure).
     */
    public void expectWarning(String warningMessage) {
        expectedWarnings.add(warningMessage);
    }
    
    /**
     * Marks an error message as expected (will not cause test failure).
     */
    public void expectError(String errorMessage) {
        expectedErrors.add(errorMessage);
    }
    
    /**
     * Clears all expected warnings and errors.
     */
    public void clearExpected() {
        expectedWarnings.clear();
        expectedErrors.clear();
    }
}
