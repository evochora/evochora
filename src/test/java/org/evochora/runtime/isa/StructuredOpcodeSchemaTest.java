package org.evochora.runtime.isa;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the semantic properties of the structured opcode ID schema.
 *
 * <p>These tests verify <em>behavior</em>, not specific ID values:
 * <ul>
 *   <li>All registered instructions have valid structured IDs</li>
 *   <li>No duplicate IDs exist</li>
 *   <li>Related instructions share the same family and operation</li>
 *   <li>The schema supports meaningful mutation semantics</li>
 * </ul>
 *
 * <p>This approach ensures tests remain valid even if specific ID assignments change,
 * as long as the schema's semantic properties are preserved.
 */
@Tag("unit")
class StructuredOpcodeSchemaTest {

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    // ========== Schema Integrity Tests ==========

    @Test
    void allRegisteredInstructions_haveUniqueIds() {
        List<Instruction.InstructionInfo> instructions = Instruction.getInstructionSetInfo();
        Set<Integer> seenIds = new HashSet<>();

        for (Instruction.InstructionInfo info : instructions) {
            boolean isNew = seenIds.add(info.opcodeId());
            assertTrue(isNew,
                "Duplicate opcode ID " + info.opcodeId() + " for instruction " + info.name());
        }
    }

    @Test
    void allRegisteredInstructions_haveValidStructuredIds() {
        List<Instruction.InstructionInfo> instructions = Instruction.getInstructionSetInfo();

        for (Instruction.InstructionInfo info : instructions) {
            int id = info.opcodeId();
            int family = OpcodeId.extractFamily(id);
            int operation = OpcodeId.extractOperation(id);
            int variant = OpcodeId.extractVariant(id);

            // Verify components are within valid ranges
            assertTrue(family >= 0 && family <= OpcodeId.MAX_FAMILY,
                info.name() + " has invalid family: " + family);
            assertTrue(operation >= 0 && operation <= OpcodeId.MAX_OPERATION,
                info.name() + " has invalid operation: " + operation);
            assertTrue(variant >= 0 && variant <= OpcodeId.MAX_VARIANT,
                info.name() + " has invalid variant: " + variant);

            // Verify round-trip
            int recomputed = OpcodeId.compute(family, operation, variant);
            assertEquals(id, recomputed,
                info.name() + " ID does not match recomputed value");
        }
    }

    @Test
    void instructionRegistry_isNotEmpty() {
        List<Instruction.InstructionInfo> instructions = Instruction.getInstructionSetInfo();
        assertFalse(instructions.isEmpty(), "No instructions registered");
        assertTrue(instructions.size() > 100, "Expected >100 instructions, got " + instructions.size());
    }

    // ========== Semantic Grouping Tests ==========

    @ParameterizedTest(name = "{0} and {1} should be in the same family")
    @CsvSource({
        "ADDR, ADDI",   // Arithmetic variants
        "ADDR, ADDS",
        "ANDR, ANDI",   // Bitwise variants
        "ANDR, ANDS",
        "IFR, IFI",     // Conditional variants
        "IFR, IFS",
        "PUSH, PUSI",   // Data variants
        "JMPR, JMPI",   // Control variants
        "PEEK, PEKI"    // Environment variants
    })
    void variantsOfSameOperation_shareFamily(String instr1, String instr2) {
        int id1 = getIdByName(instr1);
        int id2 = getIdByName(instr2);

        assertEquals(OpcodeId.extractFamily(id1), OpcodeId.extractFamily(id2),
            instr1 + " and " + instr2 + " should be in the same family");
    }

    @ParameterizedTest(name = "{0} and {1} should be in the same operation")
    @CsvSource({
        "ADDR, ADDI",   // ADD variants
        "ADDR, ADDS",
        "SUBR, SUBI",   // SUB variants
        "SUBR, SUBS",
        "ANDR, ANDI",   // AND variants
        "ANDR, ANDS",
        "ORR, ORI",     // OR variants
        "ORR, ORS"
    })
    void variantsOfSameOperation_shareOperation(String instr1, String instr2) {
        int id1 = getIdByName(instr1);
        int id2 = getIdByName(instr2);

        assertEquals(OpcodeId.extractFamily(id1), OpcodeId.extractFamily(id2),
            instr1 + " and " + instr2 + " should be in the same family");
        assertEquals(OpcodeId.extractOperation(id1), OpcodeId.extractOperation(id2),
            instr1 + " and " + instr2 + " should be in the same operation");
    }

    @ParameterizedTest(name = "{0} and {1} should be in DIFFERENT families")
    @CsvSource({
        "ADDR, ANDR",   // Arithmetic vs Bitwise
        "ADDR, IFR",    // Arithmetic vs Conditional
        "ANDR, JMPR",   // Bitwise vs Control
        "PUSH, PEEK"    // Data vs Environment
    })
    void differentInstructionTypes_haveDifferentFamilies(String instr1, String instr2) {
        int id1 = getIdByName(instr1);
        int id2 = getIdByName(instr2);

        assertNotEquals(OpcodeId.extractFamily(id1), OpcodeId.extractFamily(id2),
            instr1 + " and " + instr2 + " should be in different families");
    }

    // ========== Mutation Semantics Tests ==========

    @Test
    void mutatingVariantBit_producesValidInstructionOrGap() {
        // For any instruction, mutating just the variant should either:
        // 1. Produce another valid instruction in the same operation, OR
        // 2. Hit an unused slot (which is fine - not all variants are used)

        int addrId = getIdByName("ADDR");
        int family = OpcodeId.extractFamily(addrId);
        int operation = OpcodeId.extractOperation(addrId);

        // ADDI should be a small variant delta away from ADDR
        int addiId = getIdByName("ADDI");
        assertEquals(family, OpcodeId.extractFamily(addiId), "ADDI should be in same family");
        assertEquals(operation, OpcodeId.extractOperation(addiId), "ADDI should be in same operation");

        // The difference should be purely in variant
        int variantDelta = Math.abs(OpcodeId.extractVariant(addrId) - OpcodeId.extractVariant(addiId));
        assertTrue(variantDelta > 0 && variantDelta < OpcodeId.OPERATION_MULTIPLIER,
            "Variant delta should be small");
    }

    @Test
    void idsWithinSameFamily_haveSmallDifference() {
        // Instructions in the same family should have IDs within FAMILY_MULTIPLIER of each other
        int addrId = getIdByName("ADDR");
        int subId = getIdByName("SUBR");

        int diff = Math.abs(addrId - subId);
        assertTrue(diff < OpcodeId.FAMILY_MULTIPLIER,
            "ADDR and SUBR should be within same family range, diff=" + diff);
    }

    @Test
    void idsInDifferentFamilies_haveLargeDifference() {
        // Instructions in different families should differ by at least FAMILY_MULTIPLIER
        int addrId = getIdByName("ADDR");  // Arithmetic
        int andrId = getIdByName("ANDR");  // Bitwise

        int diff = Math.abs(addrId - andrId);
        assertTrue(diff >= OpcodeId.FAMILY_MULTIPLIER,
            "ADDR and ANDR should be in different families, diff=" + diff);
    }

    // ========== NOP Special Case ==========

    @Test
    void nop_hasIdZero() {
        // NOP at ID 0 is a fundamental design decision (family 0, operation 0, variant 0)
        assertEquals(0, getIdByName("NOP"), "NOP should have ID 0");
    }

    // ========== Helper ==========

    private int getIdByName(String name) {
        Integer id = Instruction.getInstructionIdByName(name);
        if (id == null) {
            fail("Unknown instruction: " + name);
        }
        return id;
    }
}
