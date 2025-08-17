package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.compiler.internal.legacy.AssemblerOutput;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.internal.services.ProcedureCallHandler;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class ControlFlowInstruction extends Instruction {

    public ControlFlowInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public java.util.List<Operand> resolveOperands(Environment environment) {
        String opName = getName();
        if ("CALL".equals(opName) || "JMPI".equals(opName)) {
            java.util.List<Operand> resolved = new java.util.ArrayList<>();
            int[] currentIp = organism.getIpBeforeFetch();

            int[] delta = new int[Config.WORLD_DIMENSIONS];
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
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
    public void execute(Simulation simulation) {
        ProgramArtifact artifact = simulation.getProgramArtifacts().get(organism.getProgramId());
        // KORREKTUR: performanceMode wird an den Kontext übergeben
        ExecutionContext context = new ExecutionContext(organism, simulation.getEnvironment(), artifact, simulation.isPerformanceMode());
        ProcedureCallHandler callHandler = new ProcedureCallHandler(context);

        String opName = getName();
        List<Operand> operands = resolveOperands(simulation.getEnvironment());

        try {
            switch (opName) {
                case "CALL":
                    int[] targetDelta = (int[]) operands.get(0).value();
                    callHandler.executeCall(targetDelta);
                    break;
                case "RET":
                    callHandler.executeReturn();
                    break;
                case "JMPI":
                case "JMPR":
                case "JMPS":
                    int[] delta = (int[]) operands.get(0).value();
                    int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), delta, simulation.getEnvironment());
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

    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new ControlFlowInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();

        switch (name) {
            case "JMPI", "CALL":
                if (args.length != 1) throw new IllegalArgumentException(name + " expects 1 label argument.");
                return new AssemblerOutput.JumpInstructionRequest(args[0]);
            case "JMPR":
                if (args.length != 1) throw new IllegalArgumentException("JMPR expects 1 register argument.");
                Integer regId = resolveRegToken(args[0], registerMap);
                if (regId == null) throw new IllegalArgumentException("Invalid register for JMPR.");
                return new AssemblerOutput.CodeSequence(List.of(new Molecule(Config.TYPE_DATA, regId).toInt()));
            case "JMPS":
            case "RET":
                if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
                return new AssemblerOutput.CodeSequence(Collections.emptyList());
        }
        throw new IllegalArgumentException("Cannot assemble unknown control flow instruction: " + name);
    }
}
