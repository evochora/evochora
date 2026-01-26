# ISA Restructuring Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> **IMPORTANT:** Do NOT perform any git operations. The user handles all git commits manually.

**Goal:** Restructure EvoASM opcode IDs to encode semantic similarity, enabling mutation plugins to make meaningful changes through simple arithmetic on opcode IDs.

**Architecture:** Introduce a structured 14-bit opcode scheme `[FFFF][OOOOO][VVVVV]` (Family.Operation.Variant) while maintaining backward compatibility through the existing `IInstructionSet` interface abstraction. The compiler and runtime use name-based lookups, so only the ID assignment in `Instruction.init()` needs to change.

**Tech Stack:** Java 21, JUnit 5, existing Evochora compiler/runtime infrastructure

---

## Prerequisites

- Read and understand: `docs/proposals/ISA_EXTENSION_AND_REARRANGEMENT.md`
- Ensure `./gradlew test` passes before starting

---

## Task 1: Create OpcodeId Helper Class

**Files:**
- Create: `src/main/java/org/evochora/runtime/isa/OpcodeId.java`
- Test: `src/test/java/org/evochora/runtime/isa/OpcodeIdTest.java`

**Step 1: Write the failing test**

```java
package org.evochora.runtime.isa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class OpcodeIdTest {

    @Test
    void computeId_family0_operation0_variant0_returns0() {
        assertEquals(0, OpcodeId.compute(0, 0, 0));
    }

    @Test
    void computeId_family1_operation0_variant0_returns1024() {
        // Family 1 = 1 * 1024 = 1024
        assertEquals(1024, OpcodeId.compute(1, 0, 0));
    }

    @Test
    void computeId_family1_operation0_variant16_returns1040() {
        // Family 1, Operation 0, Variant 16 (R,R) = 1024 + 0 + 16 = 1040
        assertEquals(1040, OpcodeId.compute(1, 0, 16));
    }

    @Test
    void computeId_family1_operation1_variant16_returns1072() {
        // Family 1, Operation 1, Variant 16 = 1024 + 32 + 16 = 1072
        assertEquals(1072, OpcodeId.compute(1, 1, 16));
    }

    @Test
    void extractFamily_from1072_returns1() {
        assertEquals(1, OpcodeId.extractFamily(1072));
    }

    @Test
    void extractOperation_from1072_returns1() {
        assertEquals(1, OpcodeId.extractOperation(1072));
    }

    @Test
    void extractVariant_from1072_returns16() {
        assertEquals(16, OpcodeId.extractVariant(1072));
    }

    @Test
    void computeId_invalidFamily_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> OpcodeId.compute(16, 0, 0));
    }

    @Test
    void computeId_invalidOperation_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> OpcodeId.compute(0, 32, 0));
    }

    @Test
    void computeId_invalidVariant_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> OpcodeId.compute(0, 0, 32));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "OpcodeIdTest" -i`
Expected: Compilation failure - OpcodeId class does not exist

**Step 3: Write minimal implementation**

```java
package org.evochora.runtime.isa;

/**
 * Helper class for computing and decomposing structured opcode IDs.
 *
 * <p>Opcode structure: {@code [FFFF][OOOOO][VVVVV]} = 14 bits total
 * <ul>
 *   <li>Family: 4 bits (0-15), position 10-13</li>
 *   <li>Operation: 5 bits (0-31), position 5-9</li>
 *   <li>Variant: 5 bits (0-31), position 0-4</li>
 * </ul>
 *
 * <p>Formula: {@code opcode = (family * 1024) + (operation * 32) + variant}
 *
 * @see <a href="file:../../../../../docs/proposals/ISA_EXTENSION_AND_REARRANGEMENT.md">ISA Proposal</a>
 */
public final class OpcodeId {

    /** Bits allocated for variant (operand source combination). */
    public static final int VARIANT_BITS = 5;
    /** Bits allocated for operation within family. */
    public static final int OPERATION_BITS = 5;
    /** Bits allocated for instruction family. */
    public static final int FAMILY_BITS = 4;

    /** Maximum variant value (0-31). */
    public static final int MAX_VARIANT = (1 << VARIANT_BITS) - 1;
    /** Maximum operation value (0-31). */
    public static final int MAX_OPERATION = (1 << OPERATION_BITS) - 1;
    /** Maximum family value (0-15). */
    public static final int MAX_FAMILY = (1 << FAMILY_BITS) - 1;

    /** Multiplier to shift operation into correct bit position. */
    public static final int OPERATION_MULTIPLIER = 1 << VARIANT_BITS; // 32
    /** Multiplier to shift family into correct bit position. */
    public static final int FAMILY_MULTIPLIER = 1 << (VARIANT_BITS + OPERATION_BITS); // 1024

    private OpcodeId() {
        // Utility class
    }

    /**
     * Computes a structured opcode ID from family, operation, and variant.
     *
     * @param family instruction family (0-15)
     * @param operation operation within family (0-31)
     * @param variant operand variant (0-31)
     * @return the computed opcode ID
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static int compute(int family, int operation, int variant) {
        if (family < 0 || family > MAX_FAMILY) {
            throw new IllegalArgumentException("Family must be 0-" + MAX_FAMILY + ", got: " + family);
        }
        if (operation < 0 || operation > MAX_OPERATION) {
            throw new IllegalArgumentException("Operation must be 0-" + MAX_OPERATION + ", got: " + operation);
        }
        if (variant < 0 || variant > MAX_VARIANT) {
            throw new IllegalArgumentException("Variant must be 0-" + MAX_VARIANT + ", got: " + variant);
        }
        return (family * FAMILY_MULTIPLIER) + (operation * OPERATION_MULTIPLIER) + variant;
    }

    /**
     * Extracts the family component from an opcode ID.
     *
     * @param opcodeId the full opcode ID
     * @return the family (0-15)
     */
    public static int extractFamily(int opcodeId) {
        return opcodeId / FAMILY_MULTIPLIER;
    }

    /**
     * Extracts the operation component from an opcode ID.
     *
     * @param opcodeId the full opcode ID
     * @return the operation (0-31)
     */
    public static int extractOperation(int opcodeId) {
        return (opcodeId % FAMILY_MULTIPLIER) / OPERATION_MULTIPLIER;
    }

    /**
     * Extracts the variant component from an opcode ID.
     *
     * @param opcodeId the full opcode ID
     * @return the variant (0-31)
     */
    public static int extractVariant(int opcodeId) {
        return opcodeId % OPERATION_MULTIPLIER;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "OpcodeIdTest" -i`
Expected: All 10 tests PASS

---

## Task 2: Define Variant Constants

**Files:**
- Create: `src/main/java/org/evochora/runtime/isa/Variant.java`
- Test: `src/test/java/org/evochora/runtime/isa/VariantTest.java`

**Step 1: Write the failing test**

```java
package org.evochora.runtime.isa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class VariantTest {

    @Test
    void noArg_is0() {
        assertEquals(0, Variant.NONE);
    }

    @Test
    void oneArg_R_is8() {
        assertEquals(8, Variant.R);
    }

    @Test
    void oneArg_I_is9() {
        assertEquals(9, Variant.I);
    }

    @Test
    void oneArg_S_is10() {
        assertEquals(10, Variant.S);
    }

    @Test
    void oneArg_V_is11() {
        assertEquals(11, Variant.V);
    }

    @Test
    void oneArg_L_is12() {
        assertEquals(12, Variant.L);
    }

    @Test
    void twoArg_RR_is16() {
        assertEquals(16, Variant.RR);
    }

    @Test
    void twoArg_RI_is17() {
        assertEquals(17, Variant.RI);
    }

    @Test
    void twoArg_SS_is21() {
        assertEquals(21, Variant.SS);
    }

    @Test
    void twoArg_LL_is23() {
        assertEquals(23, Variant.LL);
    }

    @Test
    void threeArg_RRR_is28() {
        assertEquals(28, Variant.RRR);
    }

    @Test
    void threeArg_RRI_is29() {
        assertEquals(29, Variant.RRI);
    }

    @Test
    void threeArg_RII_is30() {
        assertEquals(30, Variant.RII);
    }

    @Test
    void threeArg_SSS_is31() {
        assertEquals(31, Variant.SSS);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "VariantTest" -i`
Expected: Compilation failure - Variant class does not exist

**Step 3: Write minimal implementation**

```java
package org.evochora.runtime.isa;

/**
 * Constants for operand variant encodings in the structured opcode scheme.
 *
 * <p>Variants are grouped by argument count for natural mutation transitions:
 * <ul>
 *   <li>0-7: Zero-argument variants</li>
 *   <li>8-15: One-argument variants</li>
 *   <li>16-27: Two-argument variants</li>
 *   <li>28-31: Three-argument variants</li>
 * </ul>
 *
 * @see OpcodeId
 * @see <a href="file:../../../../../docs/proposals/ISA_EXTENSION_AND_REARRANGEMENT.md">ISA Proposal</a>
 */
public final class Variant {

    private Variant() {
        // Constants class
    }

    // ========== 0-Argument Variants (0-7) ==========

    /** No operands. */
    public static final int NONE = 0;

    // 1-7 reserved for future 0-arg variants

    // ========== 1-Argument Variants (8-15) ==========

    /** One register operand. */
    public static final int R = 8;

    /** One immediate operand. */
    public static final int I = 9;

    /** One stack value (peek/pop). */
    public static final int S = 10;

    /** One vector operand. */
    public static final int V = 11;

    /** One label/location register operand. */
    public static final int L = 12;

    // 13-15 reserved for future 1-arg variants

    // ========== 2-Argument Variants (16-27) ==========

    /** Two registers (dest, src). */
    public static final int RR = 16;

    /** Register + immediate. */
    public static final int RI = 17;

    /** Register + stack. */
    public static final int RS = 18;

    /** Register + vector. */
    public static final int RV = 19;

    /** Register + location register. */
    public static final int RL = 20;

    /** Two stack values. */
    public static final int SS = 21;

    /** Stack + vector. */
    public static final int SV = 22;

    /** Two location registers. */
    public static final int LL = 23;

    // 24-27 reserved for future 2-arg variants

    // ========== 3-Argument Variants (28-31) ==========

    /** Three registers. */
    public static final int RRR = 28;

    /** Two registers + immediate. */
    public static final int RRI = 29;

    /** Register + two immediates. */
    public static final int RII = 30;

    /** Three stack values. */
    public static final int SSS = 31;
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "VariantTest" -i`
Expected: All 14 tests PASS

---

## Task 3: Define Family Constants

**Files:**
- Create: `src/main/java/org/evochora/runtime/isa/Family.java`
- Test: `src/test/java/org/evochora/runtime/isa/FamilyTest.java`

**Step 1: Write the failing test**

```java
package org.evochora.runtime.isa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class FamilyTest {

    @Test
    void special_is0() {
        assertEquals(0, Family.SPECIAL);
    }

    @Test
    void arithmetic_is1() {
        assertEquals(1, Family.ARITHMETIC);
    }

    @Test
    void bitwise_is2() {
        assertEquals(2, Family.BITWISE);
    }

    @Test
    void data_is3() {
        assertEquals(3, Family.DATA);
    }

    @Test
    void conditional_is4() {
        assertEquals(4, Family.CONDITIONAL);
    }

    @Test
    void control_is5() {
        assertEquals(5, Family.CONTROL);
    }

    @Test
    void environment_is6() {
        assertEquals(6, Family.ENVIRONMENT);
    }

    @Test
    void state_is7() {
        assertEquals(7, Family.STATE);
    }

    @Test
    void location_is8() {
        assertEquals(8, Family.LOCATION);
    }

    @Test
    void vector_is9() {
        assertEquals(9, Family.VECTOR);
    }

    @Test
    void baseId_arithmetic_is1024() {
        assertEquals(1024, Family.baseId(Family.ARITHMETIC));
    }

    @Test
    void baseId_conditional_is4096() {
        assertEquals(4096, Family.baseId(Family.CONDITIONAL));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "FamilyTest" -i`
Expected: Compilation failure - Family class does not exist

**Step 3: Write minimal implementation**

```java
package org.evochora.runtime.isa;

/**
 * Constants for instruction family IDs in the structured opcode scheme.
 *
 * <p>Families group semantically related instructions. Each family has a
 * dedicated range of 1024 opcode IDs (32 operations × 32 variants).
 *
 * @see OpcodeId
 * @see <a href="file:../../../../../docs/proposals/ISA_EXTENSION_AND_REARRANGEMENT.md">ISA Proposal</a>
 */
public final class Family {

    private Family() {
        // Constants class
    }

    /** Special instructions (NOP, reserved). Range: 0-1023. */
    public static final int SPECIAL = 0;

    /** Arithmetic operations (ADD, SUB, MUL, DIV, etc.). Range: 1024-2047. */
    public static final int ARITHMETIC = 1;

    /** Bitwise operations (AND, OR, XOR, NOT, shifts). Range: 2048-3071. */
    public static final int BITWISE = 2;

    /** Data movement and stack operations. Range: 3072-4095. */
    public static final int DATA = 3;

    /** Conditional branching. Range: 4096-5119. */
    public static final int CONDITIONAL = 4;

    /** Control flow (JMP, CALL, RET). Range: 5120-6143. */
    public static final int CONTROL = 5;

    /** Environment interaction (PEEK, POKE). Range: 6144-7167. */
    public static final int ENVIRONMENT = 6;

    /** Organism state operations. Range: 7168-8191. */
    public static final int STATE = 7;

    /** Location stack/register operations. Range: 8192-9215. */
    public static final int LOCATION = 8;

    /** Vector manipulation. Range: 9216-10239. */
    public static final int VECTOR = 9;

    // Families 10-15 reserved for future expansion

    /**
     * Computes the base opcode ID for a family.
     *
     * @param family the family constant
     * @return the first opcode ID in that family's range
     */
    public static int baseId(int family) {
        return family * OpcodeId.FAMILY_MULTIPLIER;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "FamilyTest" -i`
Expected: All 12 tests PASS

---

## Task 4: Create ID Mapping Test for Arithmetic Family

**Files:**
- Test: `src/test/java/org/evochora/runtime/isa/NewOpcodeIdMappingTest.java`

This test documents the expected new IDs and will fail until we update `Instruction.init()`.

**Step 1: Write the failing test**

```java
package org.evochora.runtime.isa;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that instruction names map to their new structured opcode IDs.
 * These tests document the expected ID assignments per ISA_EXTENSION_AND_REARRANGEMENT.md.
 */
@Tag("unit")
class NewOpcodeIdMappingTest {

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    // ========== Family 0: Special ==========

    @Test
    void nop_hasId_0() {
        assertEquals(0, getIdByName("NOP"));
    }

    // ========== Family 1: Arithmetic ==========

    @Test
    void addr_hasId_1040() {
        // Family 1, Operation 0 (ADD), Variant 16 (R,R)
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 0, Variant.RR), getIdByName("ADDR"));
    }

    @Test
    void addi_hasId_1041() {
        // Family 1, Operation 0 (ADD), Variant 17 (R,I)
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 0, Variant.RI), getIdByName("ADDI"));
    }

    @Test
    void adds_hasId_1045() {
        // Family 1, Operation 0 (ADD), Variant 21 (S,S)
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 0, Variant.SS), getIdByName("ADDS"));
    }

    @Test
    void subr_hasId_1072() {
        // Family 1, Operation 1 (SUB), Variant 16 (R,R)
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 1, Variant.RR), getIdByName("SUBR"));
    }

    @Test
    void subi_hasId_1073() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 1, Variant.RI), getIdByName("SUBI"));
    }

    @Test
    void subs_hasId_1077() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 1, Variant.SS), getIdByName("SUBS"));
    }

    @Test
    void mulr_hasId_1104() {
        // Family 1, Operation 2 (MUL), Variant 16 (R,R)
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 2, Variant.RR), getIdByName("MULR"));
    }

    @Test
    void muli_hasId_1105() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 2, Variant.RI), getIdByName("MULI"));
    }

    @Test
    void muls_hasId_1109() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 2, Variant.SS), getIdByName("MULS"));
    }

    @Test
    void divr_hasId_1136() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 3, Variant.RR), getIdByName("DIVR"));
    }

    @Test
    void modr_hasId_1168() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 4, Variant.RR), getIdByName("MODR"));
    }

    // DOT and CRS (operations 12, 13)
    @Test
    void dotr_hasId_1436() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 12, Variant.RRR), getIdByName("DOTR"));
    }

    @Test
    void dots_hasId_1429() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 12, Variant.SS), getIdByName("DOTS"));
    }

    @Test
    void crsr_hasId_1468() {
        assertEquals(OpcodeId.compute(Family.ARITHMETIC, 13, Variant.RRR), getIdByName("CRSR"));
    }

    private int getIdByName(String name) {
        return Instruction.getInstructionIdByName(name)
            .orElseThrow(() -> new AssertionError("Unknown instruction: " + name));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "NewOpcodeIdMappingTest" -i`
Expected: FAIL - current IDs don't match new structured IDs

**Step 3: Record current state (no implementation yet)**

This test documents what we WANT. We'll make it pass in subsequent tasks.

---

## Task 5: Update Instruction.init() - Family 0 (Special)

**Files:**
- Modify: `src/main/java/org/evochora/runtime/isa/Instruction.java`

**Step 1: Locate the NOP registration**

Find the line in `init()` that registers NOP (currently around line 406).

**Step 2: Update NOP to use structured ID**

Change from:
```java
registerFamily(NopInstruction.class, 1, Map.of(0, "NOP"), List.of());
```

To:
```java
// Family 0: Special
registerFamily(NopInstruction.class, 1, Map.of(
    OpcodeId.compute(Family.SPECIAL, 0, Variant.NONE), "NOP"
), List.of());
```

**Step 3: Run NOP test**

Run: `./gradlew test --tests "NewOpcodeIdMappingTest.nop_hasId_0" -i`
Expected: PASS (NOP ID 0 is same in both schemes)

**Step 4: Run full test suite to check for regressions**

Run: `./gradlew test`
Expected: All existing tests still pass

---

## Task 6: Update Instruction.init() - Family 1 (Arithmetic)

**Files:**
- Modify: `src/main/java/org/evochora/runtime/isa/Instruction.java`

**Step 1: Locate arithmetic registrations**

Find `ArithmeticInstruction.class` registrations (around lines 281-284).

**Step 2: Update ADD operations**

Change from:
```java
registerFamily(ArithmeticInstruction.class, 2, Map.of(
    4, "ADDR",
    30, "ADDI"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
```

To:
```java
// Family 1, Operation 0: ADD
registerFamily(ArithmeticInstruction.class, 2, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 0, Variant.RR), "ADDR",
    OpcodeId.compute(Family.ARITHMETIC, 0, Variant.RI), "ADDI"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
```

**Step 3: Update SUB operations**

```java
// Family 1, Operation 1: SUB
registerFamily(ArithmeticInstruction.class, 2, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 1, Variant.RR), "SUBR",
    OpcodeId.compute(Family.ARITHMETIC, 1, Variant.RI), "SUBI"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
```

**Step 4: Update MUL, DIV, MOD operations**

```java
// Family 1, Operation 2: MUL
registerFamily(ArithmeticInstruction.class, 2, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 2, Variant.RR), "MULR",
    OpcodeId.compute(Family.ARITHMETIC, 2, Variant.RI), "MULI"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER));

// Family 1, Operation 3: DIV
registerFamily(ArithmeticInstruction.class, 2, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 3, Variant.RR), "DIVR",
    OpcodeId.compute(Family.ARITHMETIC, 3, Variant.RI), "DIVI"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER));

// Family 1, Operation 4: MOD
registerFamily(ArithmeticInstruction.class, 2, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 4, Variant.RR), "MODR",
    OpcodeId.compute(Family.ARITHMETIC, 4, Variant.RI), "MODI"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER));
```

**Step 5: Update stack variants (ADDS, SUBS, etc.)**

Find and update the stack-based arithmetic registrations:

```java
// Family 1, Operation 0: ADD (stack)
registerFamily(ArithmeticInstruction.class, 1, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 0, Variant.SS), "ADDS"
), List.of(OperandSource.STACK, OperandSource.STACK));

// Family 1, Operation 1: SUB (stack)
registerFamily(ArithmeticInstruction.class, 1, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 1, Variant.SS), "SUBS"
), List.of(OperandSource.STACK, OperandSource.STACK));

// ... continue for MULS, DIVS, MODS
```

**Step 6: Update DOT and CRS operations**

```java
// Family 1, Operation 12: DOT
registerFamily(ArithmeticInstruction.class, 3, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 12, Variant.RRR), "DOTR"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));

registerFamily(ArithmeticInstruction.class, 1, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 12, Variant.SS), "DOTS"
), List.of(OperandSource.STACK, OperandSource.STACK));

// Family 1, Operation 13: CRS
registerFamily(ArithmeticInstruction.class, 3, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 13, Variant.RRR), "CRSR"
), List.of(OperandSource.REGISTER, OperandSource.REGISTER, OperandSource.REGISTER));

registerFamily(ArithmeticInstruction.class, 1, Map.of(
    OpcodeId.compute(Family.ARITHMETIC, 13, Variant.SS), "CRSS"
), List.of(OperandSource.STACK, OperandSource.STACK));
```

**Step 7: Run arithmetic tests**

Run: `./gradlew test --tests "NewOpcodeIdMappingTest.addr*" --tests "NewOpcodeIdMappingTest.add*" --tests "NewOpcodeIdMappingTest.sub*" --tests "NewOpcodeIdMappingTest.mul*" --tests "NewOpcodeIdMappingTest.div*" --tests "NewOpcodeIdMappingTest.mod*" --tests "NewOpcodeIdMappingTest.dot*" --tests "NewOpcodeIdMappingTest.crs*" -i`
Expected: PASS

**Step 8: Run full test suite**

Run: `./gradlew test`
Expected: All tests pass

---

## Task 7-14: Update Remaining Families

Following the same pattern as Task 6, update each family:

- **Task 7:** Family 2 - Bitwise (AND, OR, XOR, NAD, NOT, SHL, SHR, ROT, PCN, BSN)
- **Task 8:** Family 3 - Data (SET, PUSH, POP, DUP, SWAP, DROP, SROT)
- **Task 9:** Family 4 - Conditional (IF, NE, LT, GT, LE, GE, IFT, INT, IFM, INM, IFP, INP, IFF, INF, IFV, INV)
- **Task 10:** Family 5 - Control (JMP, CALL, RET)
- **Task 11:** Family 6 - Environment (PEEK, POKE, PPK)
- **Task 12:** Family 7 - State (SCAN, SEEK, TURN, SYNC, NRG, NTR, DIFF, POS, RAND, FORK, ADP, SPN, SNT, RBI, GDV, SMR)
- **Task 13:** Family 8 - Location (DPL, SKL, LRD, LSD, PUSL, POPL, DUPL, SWPL, DRPL, ROTL, CRL, LRL)
- **Task 14:** Family 9 - Vector (VGT, VST, VBL, B2V, V2B, RTR)

Each task follows steps:
1. Write/extend failing tests for that family
2. Run tests to verify failure
3. Update `Instruction.init()` registrations
4. Run tests to verify pass
5. Run full test suite

---

## Task 15: Update NewOpcodeIdMappingTest with All Instructions

**Files:**
- Modify: `src/test/java/org/evochora/runtime/isa/NewOpcodeIdMappingTest.java`

Add test methods for ALL 193 instructions to document the complete mapping.

**Step 1: Add remaining test methods**

(Tests grouped by family, following the ISA_EXTENSION_AND_REARRANGEMENT.md document)

**Step 2: Run all mapping tests**

Run: `./gradlew test --tests "NewOpcodeIdMappingTest" -i`
Expected: All ~193 tests PASS

---

## Task 16: Verify Compiler Integration

**Files:**
- Test: Existing compiler tests

**Step 1: Run all compiler tests**

Run: `./gradlew test --tests "*Compiler*" --tests "*Lexer*" --tests "*Parser*" --tests "*Semantic*" --tests "*Emitter*" -i`
Expected: All PASS (compiler uses name-based lookups)

**Step 2: Run integration tests**

Run: `./gradlew integration`
Expected: All PASS

---

## Task 17: Verify Runtime Integration

**Files:**
- Test: Existing runtime tests

**Step 1: Run all runtime tests**

Run: `./gradlew test --tests "*VirtualMachine*" --tests "*Instruction*" --tests "*Disassembler*" -i`
Expected: All PASS

**Step 2: Run a sample program**

Run: `./gradlew run --args="compile assembly/primordial/main.evo -o /tmp/test.bin"`
Expected: Compiles successfully

---

## Task 18: Update Documentation

**Files:**
- Modify: `docs/proposals/ISA_EXTENSION_AND_REARRANGEMENT.md`

**Step 1: Add implementation status**

Add section at top:
```markdown
## Implementation Status

✅ **Implemented** on 2026-01-23

- OpcodeId helper class: `src/main/java/org/evochora/runtime/isa/OpcodeId.java`
- Variant constants: `src/main/java/org/evochora/runtime/isa/Variant.java`
- Family constants: `src/main/java/org/evochora/runtime/isa/Family.java`
- All 193 instructions migrated to structured IDs
```

---

## Task 19: Final Verification

**Step 1: Run full test suite**

Run: `./gradlew test`
Expected: All tests PASS

**Step 2: Run benchmarks (optional)**

Run: `./gradlew benchmark`
Expected: No significant performance regression

---

## Summary

| Task | Description | Estimated Steps |
|------|-------------|-----------------|
| 1 | OpcodeId helper | 4 |
| 2 | Variant constants | 4 |
| 3 | Family constants | 4 |
| 4 | Mapping test scaffold | 3 |
| 5 | Family 0 (Special) | 4 |
| 6 | Family 1 (Arithmetic) | 8 |
| 7-14 | Families 2-9 | ~7 each |
| 15 | Complete mapping tests | 2 |
| 16 | Compiler verification | 2 |
| 17 | Runtime verification | 2 |
| 18 | Documentation update | 1 |
| 19 | Final verification | 2 |

**Total: ~19 tasks, ~90 steps**
