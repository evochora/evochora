package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

public class ConditionalInstruction extends Instruction {

    public ConditionalInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        try {
            String opName = getName();
            if (opName.startsWith("IFM")) {
                List<Operand> operands = resolveOperands(environment);
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                int ownerId = environment.getOwnerId(targetCoordinate);
                if (ownerId != organism.getId()) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            List<Operand> operands = resolveOperands(environment);
            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for conditional operation.");
                return;
            }

            Operand op1 = operands.get(0);
            Operand op2 = operands.get(1);
            boolean conditionMet = false;


            if (opName.startsWith("IFT")) { // Type comparison
                int type1 = (op1.value() instanceof Integer i) ? org.evochora.runtime.model.Molecule.fromInt(i).type() : -1; // -1 for vectors
                int type2 = (op2.value() instanceof Integer i) ? Molecule.fromInt(i).type() : -1;
                conditionMet = (type1 == type2);
            } else { // Value comparison
                if (op1.value() instanceof int[] v1 && op2.value() instanceof int[] v2) {
                    conditionMet = Arrays.equals(v1, v2);
                } else if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                    Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);
                    Molecule s2 = org.evochora.runtime.model.Molecule.fromInt(i2);
                    if (Config.STRICT_TYPING && s1.type() != s2.type()) {
                        // Condition is false if types don't match in strict mode
                    } else {
                        int val1 = s1.toScalarValue();
                        int val2 = s2.toScalarValue();
                        switch (opName) {
                            case "IFR", "IFI", "IFS" -> conditionMet = (val1 == val2);
                            case "GTR", "GTI", "GTS" -> conditionMet = (val1 > val2);
                            case "LTR", "LTI", "LTS" -> conditionMet = (val1 < val2);
                            default -> organism.instructionFailed("Unknown conditional operation: " + opName);
                        }
                    }
                } else {
                    organism.instructionFailed("Mismatched operand types for comparison.");
                }
            }

            if (!conditionMet) {
                organism.skipNextInstruction(environment);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during conditional operation.");
        }
    }
}