package org.evochora.organism.instructions;

import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.World;

import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class RetInstruction extends Instruction {

    public static final int LENGTH = 1;

    public RetInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return 2;
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> rs = organism.getReturnStack();
        try {
            Object poppedValue = rs.pop();
            int[] relativeReturnIp = null;

            if (poppedValue instanceof Organism.ProcFrame frame) {
                // Restore PRs and extract return address
                organism.restorePrs(frame.savedPrs);
                relativeReturnIp = frame.relativeReturnIp;
            } else if (poppedValue instanceof int[] legacyReturnIp) {
                // Backward-compatibility: legacy frames without PR0/PR1
                relativeReturnIp = legacyReturnIp;
            } else {
                organism.instructionFailed("RET: Ungültiger Wert auf dem Return-Stack. Erwartet wurde ein Vektor als Rücksprungadresse.");
                return;
            }

            int[] initialPosition = organism.getInitialPosition();
            int[] absoluteReturnIp = new int[relativeReturnIp.length];
            for (int i = 0; i < relativeReturnIp.length; i++) {
                absoluteReturnIp[i] = initialPosition[i] + relativeReturnIp[i];
            }
            organism.setIp(absoluteReturnIp);
            organism.setSkipIpAdvance(true);
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Return stack underflow: RET auf leerem Return-Stack aufgerufen.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new RetInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("RET erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(Collections.emptyList());
    }
}