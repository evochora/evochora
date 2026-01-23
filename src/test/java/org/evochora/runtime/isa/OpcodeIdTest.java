package org.evochora.runtime.isa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpcodeId helper class.
 *
 * <p>Tests the core logic of the structured opcode ID system:
 * <ul>
 *   <li>Round-trip: compute then extract returns original components</li>
 *   <li>Validation: out-of-range values are rejected</li>
 *   <li>Arithmetic properties for mutation semantics</li>
 * </ul>
 */
@Tag("unit")
class OpcodeIdTest {

    // ========== Round-trip Tests ==========

    @ParameterizedTest(name = "family={0}, operation={1}, variant={2}")
    @CsvSource({
        "0, 0, 0",
        "1, 0, 0",
        "0, 1, 0",
        "0, 0, 1",
        "5, 10, 20",
        "9, 15, 48",
        "63, 63, 63"  // max values
    })
    void roundTrip_computeThenExtract_returnsOriginalComponents(int family, int operation, int variant) {
        int opcodeId = OpcodeId.compute(family, operation, variant);

        assertEquals(family, OpcodeId.extractFamily(opcodeId), "family mismatch");
        assertEquals(operation, OpcodeId.extractOperation(opcodeId), "operation mismatch");
        assertEquals(variant, OpcodeId.extractVariant(opcodeId), "variant mismatch");
    }

    @Test
    void compute_withZeroComponents_returnsZero() {
        assertEquals(0, OpcodeId.compute(0, 0, 0));
    }

    // ========== Validation Tests ==========

    @Test
    void compute_negativeFamiliy_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> OpcodeId.compute(-1, 0, 0));
    }

    @Test
    void compute_familyExceedsMax_throwsException() {
        assertThrows(IllegalArgumentException.class,
            () -> OpcodeId.compute(OpcodeId.MAX_FAMILY + 1, 0, 0));
    }

    @Test
    void compute_negativeOperation_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> OpcodeId.compute(0, -1, 0));
    }

    @Test
    void compute_operationExceedsMax_throwsException() {
        assertThrows(IllegalArgumentException.class,
            () -> OpcodeId.compute(0, OpcodeId.MAX_OPERATION + 1, 0));
    }

    @Test
    void compute_negativeVariant_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> OpcodeId.compute(0, 0, -1));
    }

    @Test
    void compute_variantExceedsMax_throwsException() {
        assertThrows(IllegalArgumentException.class,
            () -> OpcodeId.compute(0, 0, OpcodeId.MAX_VARIANT + 1));
    }

    // ========== Arithmetic Properties (Mutation Semantics) ==========

    @Test
    void addingVariantMultiplier_changesOnlyOperation() {
        int baseId = OpcodeId.compute(5, 10, 20);
        int mutatedId = baseId + OpcodeId.OPERATION_MULTIPLIER;

        assertEquals(OpcodeId.extractFamily(baseId), OpcodeId.extractFamily(mutatedId),
            "family should stay the same");
        assertEquals(OpcodeId.extractOperation(baseId) + 1, OpcodeId.extractOperation(mutatedId),
            "operation should increment by 1");
        assertEquals(OpcodeId.extractVariant(baseId), OpcodeId.extractVariant(mutatedId),
            "variant should stay the same");
    }

    @Test
    void addingFamilyMultiplier_changesOnlyFamily() {
        int baseId = OpcodeId.compute(5, 10, 20);
        int mutatedId = baseId + OpcodeId.FAMILY_MULTIPLIER;

        assertEquals(OpcodeId.extractFamily(baseId) + 1, OpcodeId.extractFamily(mutatedId),
            "family should increment by 1");
        assertEquals(OpcodeId.extractOperation(baseId), OpcodeId.extractOperation(mutatedId),
            "operation should stay the same");
        assertEquals(OpcodeId.extractVariant(baseId), OpcodeId.extractVariant(mutatedId),
            "variant should stay the same");
    }

    @Test
    void addingOne_changesOnlyVariant() {
        int baseId = OpcodeId.compute(5, 10, 20);
        int mutatedId = baseId + 1;

        assertEquals(OpcodeId.extractFamily(baseId), OpcodeId.extractFamily(mutatedId),
            "family should stay the same");
        assertEquals(OpcodeId.extractOperation(baseId), OpcodeId.extractOperation(mutatedId),
            "operation should stay the same");
        assertEquals(OpcodeId.extractVariant(baseId) + 1, OpcodeId.extractVariant(mutatedId),
            "variant should increment by 1");
    }

    @Test
    void variantOverflow_incrementsOperation() {
        // When variant overflows (exceeds MAX_VARIANT), operation increments
        // This is the intended mutation semantic: large enough +delta crosses operation boundary
        int baseId = OpcodeId.compute(3, 5, OpcodeId.MAX_VARIANT); // variant at max (63)
        int mutatedId = baseId + 1;

        assertEquals(OpcodeId.extractFamily(baseId), OpcodeId.extractFamily(mutatedId),
            "family should stay the same");
        assertEquals(OpcodeId.extractOperation(baseId) + 1, OpcodeId.extractOperation(mutatedId),
            "operation should increment when variant overflows");
        assertEquals(0, OpcodeId.extractVariant(mutatedId),
            "variant should wrap to 0");
    }

    @Test
    void smallMutation_fromVariantZero_staysInSameOperation() {
        // Starting from variant 0, any delta < OPERATION_MULTIPLIER stays in same operation
        int baseId = OpcodeId.compute(3, 5, 0);

        for (int delta = 1; delta < OpcodeId.OPERATION_MULTIPLIER; delta++) {
            int mutatedId = baseId + delta;
            assertEquals(OpcodeId.extractFamily(baseId), OpcodeId.extractFamily(mutatedId),
                "mutation +" + delta + " should stay in same family");
            assertEquals(OpcodeId.extractOperation(baseId), OpcodeId.extractOperation(mutatedId),
                "mutation +" + delta + " should stay in same operation");
        }
    }
}
