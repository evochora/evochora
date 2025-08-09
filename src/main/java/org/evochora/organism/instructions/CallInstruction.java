package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.World;

import java.util.Deque;
import java.util.List;
import java.util.Map;

public class CallInstruction extends Instruction {

    public static final int LENGTH = 1 + Config.WORLD_DIMENSIONS;

    private final int[] delta;

    public CallInstruction(Organism o, int[] delta, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.delta = delta;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return 2;
    }

    @Override
    public void execute(Simulation simulation) {
        int[] absoluteReturnIp = organism.getIpBeforeFetch();
        for (int i = 0; i < getLength(); i++) {
            absoluteReturnIp = organism.getNextInstructionPosition(absoluteReturnIp, simulation.getWorld(), organism.getDvBeforeFetch());
        }
        int[] initialPosition = organism.getInitialPosition();
        int[] relativeReturnIp = new int[absoluteReturnIp.length];
        for (int i = 0; i < absoluteReturnIp.length; i++) {
            relativeReturnIp[i] = absoluteReturnIp[i] - initialPosition[i];
        }
        Deque<Object> rs = organism.getReturnStack();
        if (rs.size() >= Config.RS_MAX_DEPTH) {
            organism.instructionFailed("Return stack overflow");
            return;
        }
        // Save PR snapshot along with the return address in a ProcFrame
        Object[] prSnapshot = organism.getPrs().toArray(new Object[0]);
        rs.push(new Organism.ProcFrame(relativeReturnIp, prSnapshot));
        int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), this.delta, simulation.getWorld());
        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        int[] delta = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = organism.getIp();
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult result = organism.fetchSignedArgument(currentPos, world);
            delta[i] = result.value();
            currentPos = result.nextIp();
        }
        return new CallInstruction(organism, delta, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("CALL erwartet genau 1 Argument (ein Label).");
        return new AssemblerOutput.JumpInstructionRequest(args[0]);
    }
}