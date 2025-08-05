// src/main/java/org/evochora/organism/SubInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import org.evochora.world.Symbol;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SubInstruction extends Instruction {
    // Die ID wurde bereits von dir auf 6 geändert, das übernehme ich.
    public static final int ID = 6;

    private final int reg1;
    private final int reg2;

    public SubInstruction(Organism o, int r1, int r2) {
        super(o);
        this.reg1 = r1;
        this.reg2 = r2;
    }

    static {
        Instruction.registerInstruction(SubInstruction.class, ID, "SUB", 3, SubInstruction::plan, SubInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "SUB";
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
        if (argIndex == 0 || argIndex == 1) {
            return ArgumentType.REGISTER;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für SUB: " + argIndex);
    }


    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int r2 = result2.value();

        return new SubInstruction(organism, r1, r2);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SUB erwartet 2 Argumente: %REG1 %REG2");

        int reg1Id = registerMap.get(args[0].toUpperCase());
        int reg2Id = registerMap.get(args[1].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, reg1Id).toInt(),
                new Symbol(Config.TYPE_DATA, reg2Id).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        Object val2Obj = organism.getDr(reg2);

        if (val1Obj instanceof int[] || val2Obj instanceof int[]) {
            organism.instructionFailed("SUB: Vektoren sind nicht für arithmetische Operationen erlaubt. Register (" + reg1 + ", " + reg2 + ") müssen skalare Integer sein.");
            return;
        }

        if (!(val1Obj instanceof Integer v1Raw) || !(val2Obj instanceof Integer v2Raw)) {
            organism.instructionFailed("SUB: Ungültige Registertypen. Beide Register (" + reg1 + ", " + reg2 + ") müssen skalare Integer sein.");
            return;
        }

        int v1Value = Symbol.fromInt(v1Raw).toScalarValue();
        int v2Value = Symbol.fromInt(v2Raw).toScalarValue();

        int resultValue = v1Value - v2Value;

        if (Config.STRICT_TYPING) {
            Symbol s1 = Symbol.fromInt(v1Raw);
            Symbol s2 = Symbol.fromInt(v2Raw);

            if (s1.type() != s2.type()) {
                organism.instructionFailed("SUB: Registertypen müssen übereinstimmen im strikten Modus. Reg " + reg1 + " (" + s1.type() + ") vs Reg " + reg2 + " (" + s2.type() + ").");
                return;
            }
            // KORRIGIERT: .toInt() wird aufgerufen
            organism.setDr(reg1, new Symbol(s1.type(), resultValue).toInt());

        } else {
            Symbol s1 = Symbol.fromInt(v1Raw);
            // KORRIGIERT: .toInt() wird aufgerufen
            organism.setDr(reg1, new Symbol(s1.type(), resultValue).toInt());
        }
    }
}