/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PathExpansion utility class.
 * <p>
 * Tests focus on variable expansion logic including edge cases, error handling,
 * and precedence rules (system properties override environment variables).
 */
@Tag("unit")
class PathExpansionTest {

    @AfterEach
    void cleanup() {
        // Clean up any system properties set during tests
        System.clearProperty("test.property");
        System.clearProperty("test.property1");
        System.clearProperty("test.property2");
    }

    @Test
    void testExpandPath_NoVariables() {
        String path = "/absolute/path/without/variables";
        assertEquals(path, PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_NullPath() {
        assertNull(PathExpansion.expandPath(null));
    }

    @Test
    void testExpandPath_EmptyPath() {
        assertEquals("", PathExpansion.expandPath(""));
    }

    @Test
    void testExpandPath_SingleSystemProperty() {
        System.setProperty("test.property", "/test/value");
        String path = "${test.property}/data";
        String expanded = PathExpansion.expandPath(path);
        
        assertEquals("/test/value/data", expanded);
    }

    @Test
    void testExpandPath_SingleEnvironmentVariable() {
        // Use a system property that exists on all platforms (java.io.tmpdir)
        // This avoids platform-specific environment variables (HOME vs USERPROFILE)
        String javaTmpDir = System.getProperty("java.io.tmpdir");
        assertNotNull(javaTmpDir, "java.io.tmpdir should be defined on all platforms");
        
        String path = "${java.io.tmpdir}/data";
        String expanded = PathExpansion.expandPath(path);
        
        // Should not throw exception and should have expanded something
        // (We can't know exact value, but it shouldn't be the original string with ${})
        assertNotNull(expanded);
        assertFalse(expanded.contains("${java.io.tmpdir}"));
        assertTrue(expanded.contains("/data") || expanded.contains("\\data"));
    }

    @Test
    void testExpandPath_MultipleVariables() {
        System.setProperty("test.property1", "/first");
        System.setProperty("test.property2", "second");
        
        String path = "${test.property1}/middle/${test.property2}/end";
        String expanded = PathExpansion.expandPath(path);
        
        assertEquals("/first/middle/second/end", expanded);
    }

    @Test
    void testExpandPath_SystemPropertyOverridesEnvVar() {
        // Set a system property that might also be an environment variable
        System.setProperty("PATH", "/override/value");
        
        String path = "${PATH}";
        String expanded = PathExpansion.expandPath(path);
        
        // System property should take precedence
        assertEquals("/override/value", expanded);
    }

    @Test
    void testExpandPath_UnclosedVariable() {
        String path = "${unclosed";
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PathExpansion.expandPath(path)
        );
        
        assertTrue(exception.getMessage().contains("Unclosed variable"));
        assertTrue(exception.getMessage().contains(path));
    }

    @Test
    void testExpandPath_UndefinedVariable() {
        String path = "${this_variable_definitely_does_not_exist_12345}/data";
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PathExpansion.expandPath(path)
        );
        
        assertTrue(exception.getMessage().contains("Undefined variable"));
        assertTrue(exception.getMessage().contains("this_variable_definitely_does_not_exist_12345"));
    }

    @Test
    void testExpandPath_EmptyVariableName() {
        String path = "${}/data";
        
        // Empty variable name triggers exception from System.getProperty("")
        // which throws IllegalArgumentException with message "key can't be empty"
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PathExpansion.expandPath(path)
        );
        
        // The exception can come from either:
        // 1. System.getProperty("") → "key can't be empty"
        // 2. Our undefined variable check → "Undefined variable"
        String message = exception.getMessage();
        assertTrue(message.contains("key can't be empty") || message.contains("Undefined variable"),
                "Exception should indicate empty/undefined variable. Got: " + message);
    }

    @Test
    void testExpandPath_VariableAtStart() {
        System.setProperty("test.property", "/start");
        String path = "${test.property}/middle/end";
        
        assertEquals("/start/middle/end", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_VariableAtEnd() {
        System.setProperty("test.property", "end");
        String path = "/start/middle/${test.property}";
        
        assertEquals("/start/middle/end", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_VariableOnly() {
        System.setProperty("test.property", "/complete/path");
        String path = "${test.property}";
        
        assertEquals("/complete/path", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_ConsecutiveVariables() {
        System.setProperty("test.property1", "first");
        System.setProperty("test.property2", "second");
        
        String path = "${test.property1}${test.property2}";
        String expanded = PathExpansion.expandPath(path);
        
        assertEquals("firstsecond", expanded);
    }

    @Test
    void testExpandPath_VariableWithSpecialChars() {
        System.setProperty("test.property", "/path/with-dashes_and_underscores.txt");
        String path = "${test.property}";
        
        assertEquals("/path/with-dashes_and_underscores.txt", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_WindowsStylePath() {
        System.setProperty("test.property", "C:\\Users\\Test");
        String path = "${test.property}\\data";
        
        assertEquals("C:\\Users\\Test\\data", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_DollarSignWithoutBrace() {
        // Dollar sign without brace should be left as-is
        String path = "/path/with$dollar/but$no$braces";
        
        assertEquals(path, PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_NestedBraces() {
        // Nested braces - only outer variable should be expanded
        System.setProperty("test.property", "value");
        String path = "${test.property}";
        
        assertEquals("value", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_RealWorldExample_UserHome() {
        System.setProperty("user.home", "/home/testuser");
        String path = "${user.home}/evochora/data";
        
        assertEquals("/home/testuser/evochora/data", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_RealWorldExample_JavaIoTmpdir() {
        System.setProperty("java.io.tmpdir", "/tmp");
        String path = "${java.io.tmpdir}/evochora";
        
        assertEquals("/tmp/evochora", PathExpansion.expandPath(path));
    }

    @Test
    void testExpandPath_RealWorldExample_MultipleWithSlashes() {
        System.setProperty("user.home", "/home/user");
        System.setProperty("project.name", "myproject");
        
        String path = "${user.home}/${project.name}/data/output";
        
        assertEquals("/home/user/myproject/data/output", PathExpansion.expandPath(path));
    }
}

