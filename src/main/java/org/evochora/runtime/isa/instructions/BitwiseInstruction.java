package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Handles all bitwise instructions, including standard operations like AND, OR, XOR, NOT,
 * and shifts, as well as newer ones like rotate, population count, and bit scan.
 * It supports different operand sources.
 */
public class BitwiseInstruction extends Instruction {

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
                        writeOperand(op1.rawSourceId(), result);
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
                    s2 = new Molecule(s1.type(), i2);
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
                    case "SHL" -> scalarResult = s1.toScalarValue() << s2.toScalarValue();
                    case "SHR" -> scalarResult = s1.toScalarValue() >> s2.toScalarValue();
                    default -> {
                        organism.instructionFailed("Unknown bitwise operation: " + opName);
                        return;
                    }
                }
                Object result = new Molecule(s1.type(), (int)scalarResult).toInt();

                if (op1.rawSourceId() != -1) {
                    writeOperand(op1.rawSourceId(), result);
                } else {
                    organism.getDataStack().push(result);
                }

            } else {
                organism.instructionFailed("Bitwise operations only support scalar values.");
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during bitwise operation.");
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
            Molecule amt = new Molecule(Config.TYPE_DATA, (Integer) amtObj);
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
            // Immediate uses signed literal
            amt = new Molecule(Config.TYPE_DATA, (Integer) opAmt.value());
        } else {
            amt = org.evochora.runtime.model.Molecule.fromInt((Integer) opAmt.value());
        }
        int rotated = rotate(val.toScalarValue(), amt.toScalarValue());
        if (opVal.rawSourceId() != -1) {
            writeOperand(opVal.rawSourceId(), new Molecule(val.type(), rotated).toInt());
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
            writeOperand(dest.rawSourceId(), new Molecule(src.type(), cnt).toInt());
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
                writeOperand(dest.rawSourceId(), new Molecule(src.type(), 0).toInt());
            } else {
                writeOperand(dest.rawSourceId(), new Molecule(src.type(), mask).toInt());
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
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new BitwiseInstruction(organism, fullOpcodeId);
    }
}