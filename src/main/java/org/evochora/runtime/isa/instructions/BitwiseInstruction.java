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

public class BitwiseInstruction extends Instruction {

    public BitwiseInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        try {
            List<Operand> operands = resolveOperands(context.getWorld());
            String opName = getName();

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

    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new BitwiseInstruction(organism, fullOpcodeId);
    }
}