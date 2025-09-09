package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.internal.services.ProcedureCallHandler;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Handles control flow instructions like CALL, RET, and JMP.
 * It uses a ProcedureCallHandler for CALL and RET instructions.
 */
public class ControlFlowInstruction extends Instruction {

    /**
     * Constructs a new ControlFlowInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public ControlFlowInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public java.util.List<Operand> resolveOperands(Environment environment) {
        String opName = getName();
        if ("CALL".equals(opName) || "JMPI".equals(opName)) {
            java.util.List<Operand> resolved = new java.util.ArrayList<>();
            int[] currentIp = organism.getIpBeforeFetch();

            int dims = environment.getShape().length;
            int[] delta = new int[dims];
            for (int i = 0; i < dims; i++) {
                Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
                delta[i] = res.value();
                currentIp = res.nextIp();
            }
            resolved.add(new Operand(delta, -1));
            return resolved;
        }
        return super.resolveOperands(environment);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        ProcedureCallHandler callHandler = new ProcedureCallHandler(context);
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        String opName = getName();
        List<Operand> operands = resolveOperands(environment);

        try {
            switch (opName) {
                case "CALL":
                    if (operands.size() < 1) { organism.instructionFailed("CALL requires target delta operand."); return; }
                    Object callTargetObj = operands.get(0).value();
                    if (!(callTargetObj instanceof int[])) { organism.instructionFailed("CALL target must be a vector."); return; }
                    int[] targetDelta = (int[]) operands.get(0).value();
                    callHandler.executeCall(targetDelta, artifact); // Correct!
                    break;
                case "RET":
                    callHandler.executeReturn();
                    break;
                case "JMPI":
                case "JMPR":
                case "JMPS":
                    if (operands.size() < 1) { organism.instructionFailed("Jump instruction requires target delta operand."); return; }
                    Object jumpTargetObj = operands.get(0).value();
                    if (!(jumpTargetObj instanceof int[])) { organism.instructionFailed("Jump target must be a vector."); return; }
                    int[] delta = (int[]) operands.get(0).value();
                    int[] targetIp = organism.getTargetCoordinate(organism.getInitialPosition(), delta, environment);
                    organism.setIp(targetIp);
                    organism.setSkipIpAdvance(true);
                    break;
                default:
                    organism.instructionFailed("Unknown control flow instruction: " + opName);
                    break;
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during control flow operation.");
        } catch (ClassCastException e) {
            organism.instructionFailed("Invalid operand type for control flow operation.");
        }
    }

    /**
     * Plans the execution of a control flow instruction.
     * @param organism The organism that will execute the instruction.
     * @param environment The environment in which the instruction will be executed.
     * @return The planned instruction.
     */
    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new ControlFlowInstruction(organism, fullOpcodeId);
    }
}