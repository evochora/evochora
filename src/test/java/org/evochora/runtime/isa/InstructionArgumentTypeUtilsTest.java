package org.evochora.runtime.isa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InstructionArgumentTypeUtils.
 * Tests the centralized mapping between InstructionArgumentType enum values and their string representations.
 */
@Tag("unit")
class InstructionArgumentTypeUtilsTest {

    @Test
    void testToDisplayStringWithValidTypes() {
        assertEquals("REGISTER", InstructionArgumentTypeUtils.toDisplayString(InstructionArgumentType.REGISTER));
        assertEquals("LITERAL", InstructionArgumentTypeUtils.toDisplayString(InstructionArgumentType.LITERAL));
        assertEquals("VECTOR", InstructionArgumentTypeUtils.toDisplayString(InstructionArgumentType.VECTOR));
        assertEquals("LABEL", InstructionArgumentTypeUtils.toDisplayString(InstructionArgumentType.LABEL));
    }

    @Test
    void testToDisplayStringWithNull() {
        assertEquals("UNKNOWN", InstructionArgumentTypeUtils.toDisplayString(null));
    }

    @Test
    void testFromDisplayStringWithValidStrings() {
        assertEquals(InstructionArgumentType.REGISTER, InstructionArgumentTypeUtils.fromDisplayString("REGISTER"));
        assertEquals(InstructionArgumentType.LITERAL, InstructionArgumentTypeUtils.fromDisplayString("LITERAL"));
        assertEquals(InstructionArgumentType.VECTOR, InstructionArgumentTypeUtils.fromDisplayString("VECTOR"));
        assertEquals(InstructionArgumentType.LABEL, InstructionArgumentTypeUtils.fromDisplayString("LABEL"));
    }

    @Test
    void testFromDisplayStringWithCaseInsensitive() {
        assertEquals(InstructionArgumentType.REGISTER, InstructionArgumentTypeUtils.fromDisplayString("register"));
        assertEquals(InstructionArgumentType.LITERAL, InstructionArgumentTypeUtils.fromDisplayString("literal"));
        assertEquals(InstructionArgumentType.VECTOR, InstructionArgumentTypeUtils.fromDisplayString("vector"));
        assertEquals(InstructionArgumentType.LABEL, InstructionArgumentTypeUtils.fromDisplayString("label"));
        
        assertEquals(InstructionArgumentType.REGISTER, InstructionArgumentTypeUtils.fromDisplayString("Register"));
        assertEquals(InstructionArgumentType.LITERAL, InstructionArgumentTypeUtils.fromDisplayString("Literal"));
        assertEquals(InstructionArgumentType.VECTOR, InstructionArgumentTypeUtils.fromDisplayString("Vector"));
        assertEquals(InstructionArgumentType.LABEL, InstructionArgumentTypeUtils.fromDisplayString("Label"));
    }

    @Test
    void testFromDisplayStringWithInvalidStrings() {
        assertNull(InstructionArgumentTypeUtils.fromDisplayString("INVALID"));
        assertNull(InstructionArgumentTypeUtils.fromDisplayString("UNKNOWN"));
        assertNull(InstructionArgumentTypeUtils.fromDisplayString(""));
        assertNull(InstructionArgumentTypeUtils.fromDisplayString("REGISTER_EXTENDED"));
        assertNull(InstructionArgumentTypeUtils.fromDisplayString("REGISTER "));
        assertNull(InstructionArgumentTypeUtils.fromDisplayString(" REGISTER"));
    }

    @Test
    void testFromDisplayStringWithNull() {
        assertNull(InstructionArgumentTypeUtils.fromDisplayString(null));
    }

    @Test
    void testGetDefaultDisplayString() {
        assertEquals("UNKNOWN", InstructionArgumentTypeUtils.getDefaultDisplayString());
    }

    @Test
    void testRoundTripConversion() {
        // Test that converting from enum to string and back preserves the original value
        for (InstructionArgumentType argType : InstructionArgumentType.values()) {
            String displayString = InstructionArgumentTypeUtils.toDisplayString(argType);
            InstructionArgumentType roundTrip = InstructionArgumentTypeUtils.fromDisplayString(displayString);
            assertEquals(argType, roundTrip, "Round trip conversion failed for " + argType);
        }
    }

    @Test
    void testAllEnumValuesCovered() {
        // Ensure all enum values are handled in the switch statement
        for (InstructionArgumentType argType : InstructionArgumentType.values()) {
            String result = InstructionArgumentTypeUtils.toDisplayString(argType);
            assertNotNull(result, "Result should not be null for " + argType);
            assertFalse(result.isEmpty(), "Result should not be empty for " + argType);
            assertNotEquals("UNKNOWN", result, "Result should not be UNKNOWN for valid enum value " + argType);
        }
    }
}
