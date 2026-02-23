package org.evochora.runtime.isa.instructions;

import java.util.List;
import java.util.NoSuchElementException;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles all bitwise instructions, including standard operations like AND, OR, XOR, NOT,
 * and shifts, as well as newer ones like rotate, population count, and bit scan.
 * It supports different operand sources.
 */
public class BitwiseInstruction extends Instruction {

    private static int family;

    /**
     * Registers all bitwise instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: AND
        reg(0, Variant.RR, "ANDR", REGISTER, REGISTER);
        reg(0, Variant.RI, "ANDI", REGISTER, IMMEDIATE);
        reg(0, Variant.SS, "ANDS", STACK, STACK);
        // Operation 1: OR
        reg(1, Variant.RR, "ORR", REGISTER, REGISTER);
        reg(1, Variant.RI, "ORI", REGISTER, IMMEDIATE);
        reg(1, Variant.SS, "ORS", STACK, STACK);
        // Operation 2: XOR
        reg(2, Variant.RR, "XORR", REGISTER, REGISTER);
        reg(2, Variant.RI, "XORI", REGISTER, IMMEDIATE);
        reg(2, Variant.SS, "XORS", STACK, STACK);
        // Operation 3: NAD (NAND)
        reg(3, Variant.RR, "NADR", REGISTER, REGISTER);
        reg(3, Variant.RI, "NADI", REGISTER, IMMEDIATE);
        reg(3, Variant.SS, "NADS", STACK, STACK);
        // Operation 4: NOR
        reg(4, Variant.RR, "NORR", REGISTER, REGISTER);
        reg(4, Variant.RI, "NORI", REGISTER, IMMEDIATE);
        reg(4, Variant.SS, "NORS", STACK, STACK);
        // Operation 5: EQU (XNOR / Equivalence)
        reg(5, Variant.RR, "EQUR", REGISTER, REGISTER);
        reg(5, Variant.RI, "EQUI", REGISTER, IMMEDIATE);
        reg(5, Variant.SS, "EQUS", STACK, STACK);
        // Operation 6: ADN (AND-NOT: a & ~b)
        reg(6, Variant.RR, "ADNR", REGISTER, REGISTER);
        reg(6, Variant.RI, "ADNI", REGISTER, IMMEDIATE);
        reg(6, Variant.SS, "ADNS", STACK, STACK);
        // Operation 7: ORN (OR-NOT: a | ~b)
        reg(7, Variant.RR, "ORNR", REGISTER, REGISTER);
        reg(7, Variant.RI, "ORNI", REGISTER, IMMEDIATE);
        reg(7, Variant.SS, "ORNS", STACK, STACK);
        // Operation 8: NOT
        reg(8, Variant.R, "NOT", REGISTER);
        reg(8, Variant.S, "NOTS", STACK);
        // Operation 9: SHL (Shift Left)
        reg(9, Variant.RR, "SHLR", REGISTER, REGISTER);
        reg(9, Variant.RI, "SHLI", REGISTER, IMMEDIATE);
        reg(9, Variant.SS, "SHLS", STACK, STACK);
        // Operation 10: SHR (Shift Right)
        reg(10, Variant.RR, "SHRR", REGISTER, REGISTER);
        reg(10, Variant.RI, "SHRI", REGISTER, IMMEDIATE);
        reg(10, Variant.SS, "SHRS", STACK, STACK);
        // Operation 11: ROT (Rotate)
        reg(11, Variant.RR, "ROTR", REGISTER, REGISTER);
        reg(11, Variant.RI, "ROTI", REGISTER, IMMEDIATE);
        reg(11, Variant.SS, "ROTS", STACK, STACK);
        // Operation 12: PCN (Population Count)
        reg(12, Variant.RR, "PCNR", REGISTER, REGISTER);
        reg(12, Variant.S, "PCNS", STACK);
        // Operation 13: BSN (Bit Scan N-th)
        reg(13, Variant.RRR, "BSNR", REGISTER, REGISTER, REGISTER);
        reg(13, Variant.RRI, "BSNI", REGISTER, REGISTER, IMMEDIATE);
        reg(13, Variant.SS, "BSNS", STACK, STACK);
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(BitwiseInstruction.class, BitwiseInstruction::new, family, op, variant, name, sources);
    }

    /**
     * Constructs a new BitwiseInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public BitwiseInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        try {
            List<Operand> operands = resolveOperands(context.getWorld());
            if (organism.isInstructionFailed()) {
                return;
            }
            String opName = getName();

            // --- New: Rotation (ROT*), Population Count (PCN*), Bit Scan N-th (BSN*) ---
            if (opName.startsWith("ROT")) {
                handleRotate(opName, operands);
                return;
            }

            if (opName.startsWith("PCN")) {
                handlePopCount(opName, operands);
                return;
            }

            if (opName.startsWith("BSN")) {
                handleBitScanNth(opName, operands);
                return;
            }

            // Handle NOT separately as it has only one operand
            if (opName.contains("NOT")) {
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for NOT operation.");
                    return;
                }
                Operand op1 = operands.get(0);
                if (op1.value() instanceof Integer i1) {
                    Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);
                    int resultValue = ~s1.toScalarValue();
                    Object result = new Molecule(s1.type(), resultValue).toInt();

                    if (op1.rawSourceId() != -1) {
                        if (!writeOperand(op1.rawSourceId(), result)) {
                            return;
                        }
                    } else {
                        organism.getDataStack().push(result);
                    }
                } else {
                    organism.instructionFailed("NOT operations only support scalar values.");
                }
                return;
            }

            // All other bitwise operations have two operands
            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for bitwise operation.");
                return;
            }

            Operand op1 = operands.get(0);
            Operand op2 = operands.get(1);

            if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);
                Molecule s2;
                if (op2.rawSourceId() == -1) { // Immediate
                    Molecule imm = org.evochora.runtime.model.Molecule.fromInt(i2);
                    s2 = new Molecule(s1.type(), imm.toScalarValue());
                } else { // Register
                    s2 = org.evochora.runtime.model.Molecule.fromInt(i2);
                }

                if (Config.STRICT_TYPING && s1.type() != s2.type()) {
                    organism.instructionFailed("Operand types must match in strict mode for bitwise operations.");
                    return;
                }

                // For shifts, the second operand must be DATA type
                if (opName.contains("SH") && s2.type() != Config.TYPE_DATA) {
                    organism.instructionFailed("Shift amount must be of type DATA.");
                    return;
                }

                long scalarResult;
                String baseOp = opName.substring(0, opName.length() - 1); // "ANDR" -> "AND"

                switch (baseOp) {
                    case "NAD" -> scalarResult = ~(s1.toScalarValue() & s2.toScalarValue());
                    case "AND" -> scalarResult = s1.toScalarValue() & s2.toScalarValue();
                    case "OR" -> scalarResult = s1.toScalarValue() | s2.toScalarValue();
                    case "XOR" -> scalarResult = s1.toScalarValue() ^ s2.toScalarValue();
                    case "NOR" -> scalarResult = ~(s1.toScalarValue() | s2.toScalarValue());
                    case "EQU" -> scalarResult = ~(s1.toScalarValue() ^ s2.toScalarValue());
                    case "ADN" -> scalarResult = s1.toScalarValue() & ~s2.toScalarValue();
                    case "ORN" -> scalarResult = s1.toScalarValue() | ~s2.toScalarValue();
                    case "SHL" -> scalarResult = s1.toScalarValue() << s2.toScalarValue();
                    case "SHR" -> scalarResult = s1.toScalarValue() >> s2.toScalarValue();
                    default -> {
                        organism.instructionFailed("Unknown bitwise operation: " + opName);
                        return;
                    }
                }
                Object result = new Molecule(s1.type(), (int)scalarResult).toInt();

                if (op1.rawSourceId() != -1) {
                    if (!writeOperand(op1.rawSourceId(), result)) {
                        return;
                    }
                } else {
                    organism.getDataStack().push(result);
                }

            } else {
                organism.instructionFailed("Bitwise operations only support scalar values.");
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during bitwise operation.");
            return;
        }
    }

    private void handleRotate(String opName, List<Operand> operands) {
        // ROTR %Val, %Amt | ROTI %Val, <Amt> | ROTS (Amt, Val) from stack
        if ("ROTS".equals(opName)) {
            if (operands.size() != 2) { organism.instructionFailed("ROTS requires two stack operands."); return; }
            Object amtObj = operands.get(0).value(); // top of stack first
            Object valObj = operands.get(1).value();
            if (!(amtObj instanceof Integer) || !(valObj instanceof Integer)) { organism.instructionFailed("ROTS requires scalars."); return; }
            Molecule val = org.evochora.runtime.model.Molecule.fromInt((Integer) valObj);
            Molecule amt = org.evochora.runtime.model.Molecule.fromInt((Integer) amtObj);
            int rotated = rotate(val.toScalarValue(), amt.toScalarValue());
            organism.getDataStack().push(new Molecule(val.type(), rotated).toInt());
            return;
        }

        // Register/Immediate variants
        if (operands.size() != 2) { organism.instructionFailed("ROT requires two operands."); return; }
        Operand opVal = operands.get(0);
        Operand opAmt = operands.get(1);
        if (!(opVal.value() instanceof Integer) || !(opAmt.value() instanceof Integer)) { organism.instructionFailed("ROT requires scalar operands."); return; }
        Molecule val = org.evochora.runtime.model.Molecule.fromInt((Integer) opVal.value());
        Molecule amt;
        if (opAmt.rawSourceId() == -1) {
            // Immediate: decode stored value to get scalar
            Molecule imm = org.evochora.runtime.model.Molecule.fromInt((Integer) opAmt.value());
            amt = new Molecule(Config.TYPE_DATA, imm.toScalarValue());
        } else {
            amt = org.evochora.runtime.model.Molecule.fromInt((Integer) opAmt.value());
        }
        int rotated = rotate(val.toScalarValue(), amt.toScalarValue());
        if (opVal.rawSourceId() != -1) {
            if (!writeOperand(opVal.rawSourceId(), new Molecule(val.type(), rotated).toInt())) {
                return;
            }
        } else {
            organism.instructionFailed("ROT destination must be a register.");
        }
    }

    private int rotate(int value, int amount) {
        int width = Config.VALUE_BITS;
        int mask = (1 << width) - 1;
        int v = value & mask;
        int k = amount % width;
        if (k < 0) k += width;
        return ((v << k) | (v >>> (width - k))) & mask;
    }

    private void handlePopCount(String opName, List<Operand> operands) {
        // PCNR %Dest, %Src | PCNS (pop one)
        if ("PCNS".equals(opName)) {
            if (operands.size() != 1) { organism.instructionFailed("PCNS requires one stack operand."); return; }
            Object srcObj = operands.get(0).value();
            if (!(srcObj instanceof Integer)) { organism.instructionFailed("PCNS requires scalar operand."); return; }
            Molecule src = org.evochora.runtime.model.Molecule.fromInt((Integer) srcObj);
            int cnt = Integer.bitCount(src.toScalarValue() & ((1 << Config.VALUE_BITS) - 1));
            organism.getDataStack().push(new Molecule(src.type(), cnt).toInt());
            return;
        }

        if (operands.size() != 2) { organism.instructionFailed("PCNR requires two register operands."); return; }
        Operand dest = operands.get(0);
        Operand srcOp = operands.get(1);
        if (!(dest.value() instanceof Integer) || !(srcOp.value() instanceof Integer)) { organism.instructionFailed("PCNR requires scalar registers."); return; }
        Molecule src = org.evochora.runtime.model.Molecule.fromInt((Integer) srcOp.value());
        int cnt = Integer.bitCount(src.toScalarValue() & ((1 << Config.VALUE_BITS) - 1));
        if (dest.rawSourceId() != -1) {
            if (!writeOperand(dest.rawSourceId(), new Molecule(src.type(), cnt).toInt())) {
                return;
            }
        } else {
            organism.instructionFailed("PCNR destination must be a register.");
        }
    }

    private void handleBitScanNth(String opName, List<Operand> operands) {
        // BSNR %Dest, %Src, %N | BSNI %Dest, %Src, <N> | BSNS (pop N then Src)
        if ("BSNS".equals(opName)) {
            if (operands.size() != 2) { organism.instructionFailed("BSNS requires two stack operands."); return; }
            Object nObj = operands.get(0).value();
            Object srcObj = operands.get(1).value();
            if (!(srcObj instanceof Integer) || !(nObj instanceof Integer)) { organism.instructionFailed("BSNS requires scalar operands."); return; }
            Molecule src = org.evochora.runtime.model.Molecule.fromInt((Integer) srcObj);
            int n = org.evochora.runtime.model.Molecule.fromInt((Integer) nObj).toScalarValue();
            int mask = bitScanNthMask(src.toScalarValue(), n);
            if (mask == 0) { organism.instructionFailed("BSN failed: invalid N or not enough set bits."); }
            organism.getDataStack().push(new Molecule(src.type(), mask).toInt());
            return;
        }

        // Register/Immediate variants
        if (operands.size() != 3) { organism.instructionFailed("BSN requires three operands."); return; }
        Operand dest = operands.get(0);
        Operand srcOp = operands.get(1);
        Operand nOp = operands.get(2);
        if (!(dest.value() instanceof Integer) || !(srcOp.value() instanceof Integer) || !(nOp.value() instanceof Integer)) { organism.instructionFailed("BSN requires scalar operands."); return; }
        Molecule src = org.evochora.runtime.model.Molecule.fromInt((Integer) srcOp.value());
        int n = org.evochora.runtime.model.Molecule.fromInt((Integer) nOp.value()).toScalarValue();
        int mask = bitScanNthMask(src.toScalarValue(), n);
        if (dest.rawSourceId() != -1) {
            if (mask == 0) {
                organism.instructionFailed("BSN failed: invalid N or not enough set bits.");
                if (!writeOperand(dest.rawSourceId(), new Molecule(src.type(), 0).toInt())) {
                    return;
                }
            } else {
                if (!writeOperand(dest.rawSourceId(), new Molecule(src.type(), mask).toInt())) {
                    return;
                }
            }
        } else {
            organism.instructionFailed("BSN destination must be a register.");
        }
    }

    private int bitScanNthMask(int value, int n) {
        if (n == 0) return 0;
        int width = Config.VALUE_BITS;
        int v = value & ((1 << width) - 1);
        int count = 0;
        if (n > 0) {
            // LSB -> MSB
            for (int i = 0; i < width; i++) {
                if (((v >>> i) & 1) != 0) {
                    count++;
                    if (count == n) return (1 << i) & ((1 << width) - 1);
                }
            }
            return 0;
        } else { // n < 0: MSB -> LSB
            int target = -n;
            for (int i = width - 1; i >= 0; i--) {
                if (((v >>> i) & 1) != 0) {
                    count++;
                    if (count == target) return (1 << i) & ((1 << width) - 1);
                }
            }
            return 0;
        }
    }

    /**
     * Plans the execution of a bitwise instruction.
     * @param organism The organism that will execute the instruction.
     * @param environment The environment in which the instruction will be executed.
     * @return The planned instruction.
     */
    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).value();
        return new BitwiseInstruction(organism, fullOpcodeId);
    }
}