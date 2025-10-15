package org.evochora.datapipeline.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link H2SchemaUtil}.
 * <p>
 * These tests verify the schema name sanitization logic, which converts
 * simulation run IDs into H2-compliant schema identifiers.
 */
@Tag("unit")
class H2SchemaUtilTest {

    @Test
    void testToSchemaName_ValidRunId() {
        // Standard UUID-based runId
        String runId = "20251006143025-550e8400-e29b-41d4-a716-446655440000";
        String schemaName = H2SchemaUtil.toSchemaName(runId);
        
        assertEquals("SIM_20251006143025_550E8400_E29B_41D4_A716_446655440000", schemaName);
        assertTrue(schemaName.startsWith("SIM_"));
        assertFalse(schemaName.contains("-"));
    }

    @Test
    void testToSchemaName_ShortRunId() {
        String runId = "test-run-123";
        String schemaName = H2SchemaUtil.toSchemaName(runId);
        
        assertEquals("SIM_TEST_RUN_123", schemaName);
    }

    @Test
    void testToSchemaName_SpecialCharacters() {
        // RunId with various special characters that should be replaced with underscores
        String runId = "run@2025#10-06!test$";
        String schemaName = H2SchemaUtil.toSchemaName(runId);
        
        // All special characters replaced with underscore
        assertEquals("SIM_RUN_2025_10_06_TEST_", schemaName);
        assertTrue(schemaName.matches("^[A-Z0-9_]+$"));
    }

    @Test
    void testToSchemaName_NullRunId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> H2SchemaUtil.toSchemaName(null)
        );
        
        assertEquals("Simulation run ID cannot be null or empty", exception.getMessage());
    }

    @Test
    void testToSchemaName_EmptyRunId() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> H2SchemaUtil.toSchemaName("")
        );
        
        assertEquals("Simulation run ID cannot be null or empty", exception.getMessage());
    }

    @Test
    void testToSchemaName_TooLongRunId() {
        // Create a runId that results in a schema name > 256 characters
        // "sim_" = 4 chars, so we need runId > 252 chars
        String longRunId = "x".repeat(253);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> H2SchemaUtil.toSchemaName(longRunId)
        );
        
        assertTrue(exception.getMessage().contains("Schema name too long"));
        assertTrue(exception.getMessage().contains("max 256"));
    }

    @Test
    void testToSchemaName_MaxLengthRunId() {
        // Test boundary: exactly 256 chars should work
        // "sim_" = 4 chars, so runId can be 252 chars
        String maxLengthRunId = "x".repeat(252);
        
        String schemaName = H2SchemaUtil.toSchemaName(maxLengthRunId);
        
        assertEquals(256, schemaName.length());
        assertTrue(schemaName.startsWith("SIM_"));
    }

    @Test
    void testToSchemaName_AlphanumericOnly() {
        String runId = "ABC123xyz789";
        String schemaName = H2SchemaUtil.toSchemaName(runId);
        
        assertEquals("SIM_ABC123XYZ789", schemaName);
        assertTrue(schemaName.matches("^[A-Z0-9_]+$"));
    }

    @Test
    void testToSchemaName_Uppercase() {
        // Verify that result is always uppercase (H2 requirement)
        String runId = "lowercase-UPPERCASE-MiXeD";
        String schemaName = H2SchemaUtil.toSchemaName(runId);
        
        assertEquals("SIM_LOWERCASE_UPPERCASE_MIXED", schemaName);
        assertEquals(schemaName, schemaName.toUpperCase());
    }

    @Test
    void testToSchemaName_ConsecutiveSpecialChars() {
        // Multiple consecutive special characters should result in consecutive underscores
        String runId = "test---run___id";
        String schemaName = H2SchemaUtil.toSchemaName(runId);
        
        assertEquals("SIM_TEST___RUN___ID", schemaName);
    }
}

