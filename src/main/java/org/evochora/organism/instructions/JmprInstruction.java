package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class JmprInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int reg;

    public JmprInstruction(Organism o, int r, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg = r;
    }

    @Override
    public void execute(Simulation simulation) {
        Object targetObj = organism.getDr(reg);
        if (!(targetObj instanceof int[] relativeCoord)) {
            organism.instructionFailed("JMPR: Ungültiger Registertyp für Ziel (Reg " + reg + "). Erwartet Vektor (int[]).");
            return;
        }
        if (relativeCoord.length != simulation.getWorld().getShape().length) {
            organism.instructionFailed("JMPR: Dimension des Ziel-Vektors stimmt nicht mit Welt-Dimension überein.");
            return;
        }
        int[] initialPosition = organism.getInitialPosition();
        int[] absoluteCoord = new int[relativeCoord.length];
        for (int i = 0; i < relativeCoord.length; i++) {
            absoluteCoord[i] = initialPosition[i] + relativeCoord[i];
        }
        organism.setIp(absoluteCoord);
        organism.setSkipIpAdvance(true);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new JmprInstruction(organism, r, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("JMPR erwartet 1 Argument: %REG_TARGET");
        String arg = args[0].toUpperCase();
        Integer regId = registerMap.get(arg);
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + arg);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}