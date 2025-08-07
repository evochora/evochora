// src/main/java/org/evochora/organism/IftrInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class IftrInstruction extends Instruction {
    public static final int ID = 33;

    private final int reg1;
    private final int reg2;

    public IftrInstruction(Organism o, int r1, int r2) {
        super(o);
        this.reg1 = r1;
        this.reg2 = r2;
    }

    static {
        Instruction.registerInstruction(IftrInstruction.class, ID, "IFTR", 3, IftrInstruction::plan, IftrInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "IFTR";
    }

    @Override
    public int getLength() {
        return 3;
    }

    @Override
    protected int getFixedBaseCost() {
        return 1;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        if (argIndex == 0 || argIndex == 1) return ArgumentType.REGISTER;
        throw new IllegalArgumentException("Ungültiger Argumentindex für IFTR: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int r2 = result2.value();

        return new IftrInstruction(organism, r1, r2);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) {
            throw new IllegalArgumentException("IFTR erwartet genau 2 Register-Argumente: %REG1 %REG2");
        }
        Integer reg1Id = registerMap.get(args[0].toUpperCase());
        if (reg1Id == null) {
            throw new IllegalArgumentException(String.format("Ungültiges Register-Argument für Reg1: '%s'", args[0]));
        }
        Integer reg2Id = registerMap.get(args[1].toUpperCase());
        if (reg2Id == null) {
            throw new IllegalArgumentException(String.format("Ungültiges Register-Argument für Reg2: '%s'", args[1]));
        }
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, reg1Id).toInt(),
                new Symbol(Config.TYPE_DATA, reg2Id).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        Object val2Obj = organism.getDr(reg2);

        boolean conditionMet = false;

        if (val1Obj instanceof Integer v1Raw && val2Obj instanceof Integer v2Raw) {
            Symbol s1 = Symbol.fromInt(v1Raw);
            Symbol s2 = Symbol.fromInt(v2Raw);
            conditionMet = s1.type() == s2.type();
        }

        if (!conditionMet) {
            organism.skipNextInstruction(organism.getSimulation().getWorld());
        }
    }
}