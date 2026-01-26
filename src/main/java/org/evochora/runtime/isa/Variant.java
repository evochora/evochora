package org.evochora.runtime.isa;

/**
 * Constants for operand variant encodings in the structured opcode scheme.
 *
 * <p>Variants encode the operand source combinations for instructions. They occupy
 * the lowest 6 bits of the opcode ID (positions 0-5) and are grouped by argument count
 * to enable meaningful mutation within groups.
 *
 * <p><b>Variant Groupings:</b>
 * <ul>
 *   <li>0-Argument variants: 0-15 (only NONE currently defined)</li>
 *   <li>1-Argument variants: 16-31 (R, I, S, V, L)</li>
 *   <li>2-Argument variants: 32-47 (RR, RI, RS, RV, RL, SS, SV, LL)</li>
 *   <li>3-Argument variants: 48-63 (RRR, RRI, RII, SSS, VIV)</li>
 * </ul>
 *
 * <p><b>Operand Type Legend:</b>
 * <ul>
 *   <li>R = Register</li>
 *   <li>I = Immediate value</li>
 *   <li>S = Stack value</li>
 *   <li>V = Vector</li>
 *   <li>L = Label/Location register</li>
 * </ul>
 *
 * <p>This grouping ensures that mutation within a variant range changes operand sources
 * but preserves the instruction's argument count and overall structure.
 *
 * <p>This class is thread-safe as it contains only immutable constants.
 */
public final class Variant {

    // ==================== 0-Argument Variants (0-15) ====================

    /** No operands. */
    public static final int NONE = 0;

    // ==================== 1-Argument Variants (16-31) ====================

    /** One register operand. */
    public static final int R = 16;

    /** One immediate operand. */
    public static final int I = 17;

    /** One stack value operand. */
    public static final int S = 18;

    /** One vector operand. */
    public static final int V = 19;

    /** One label/location register operand. */
    public static final int L = 20;

    // ==================== 2-Argument Variants (32-47) ====================

    /** Two register operands. */
    public static final int RR = 32;

    /** Register + immediate operands. */
    public static final int RI = 33;

    /** Register + stack operands. */
    public static final int RS = 34;

    /** Register + vector operands. */
    public static final int RV = 35;

    /** Register + location register operands. */
    public static final int RL = 36;

    /** Two stack value operands. */
    public static final int SS = 37;

    /** Stack + vector operands. */
    public static final int SV = 38;

    /** Two location register operands. */
    public static final int LL = 39;

    // ==================== 3-Argument Variants (48-63) ====================

    /** Three register operands. */
    public static final int RRR = 48;

    /** Two registers + immediate operands. */
    public static final int RRI = 49;

    /** Register + two immediate operands. */
    public static final int RII = 50;

    /** Three stack value operands. */
    public static final int SSS = 51;

    /** Vector + immediate + vector operands (used by FRKI). */
    public static final int VIV = 52;

    private Variant() {
        // Constants class - prevent instantiation
    }
}
