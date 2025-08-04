// src/main/java/org/evochora/organism/IfInstruction.java
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

public class IfInstruction extends Instruction {
    public static final int ID = 7;
    public static final int ID_LT = 8;
    public static final int ID_GT = 9;

    private final int reg1;
    private final int reg2;
    private final int fullOpcodeId;

    public IfInstruction(Organism o, int r1, int r2, int fullOpcodeId) {
        super(o);
        this.reg1 = r1;
        this.reg2 = r2;
        this.fullOpcodeId = fullOpcodeId;
    }

    static {
        Instruction.registerInstruction(IfInstruction.class, ID, "IF", 3, IfInstruction::plan, IfInstruction::assemble);
        Instruction.registerInstruction(IfInstruction.class, ID_LT, "IFLT", 3, IfInstruction::plan, IfInstruction::assemble);
        Instruction.registerInstruction(IfInstruction.class, ID_GT, "IFGT", 3, IfInstruction::plan, IfInstruction::assemble);
    }

    @Override
    public String getName() {
        return Instruction.getInstructionNameById(fullOpcodeId);
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für " + getName() + ": " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();

        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int r2 = result2.value();

        return new IfInstruction(organism, r1, r2, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("IF-Befehle erwarten 2 Argumente: %REG1 %REG2");

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
            organism.instructionFailed("IF: Vektoren sind nicht für Vergleichsoperationen erlaubt. Register (" + reg1 + ", " + reg2 + ") müssen skalare Integer sein.");
            return;
        }

        if (!(val1Obj instanceof Integer v1Raw) || !(val2Obj instanceof Integer v2Raw)) {
            organism.instructionFailed("IF: Ungültige Registertypen. Beide Register (" + reg1 + ", " + reg2 + ") müssen skalare Integer sein.");
            return;
        }

        int v1 = Symbol.fromInt(v1Raw).toScalarValue();
        int v2 = Symbol.fromInt(v2Raw).toScalarValue();

        if (Config.STRICT_TYPING) {
            Symbol s1 = Symbol.fromInt(v1Raw);
            Symbol s2 = Symbol.fromInt(v2Raw);

            if (s1.type() != s2.type()) {
                organism.instructionFailed("IF: Registertypen müssen übereinstimmen im strikten Modus. Reg " + reg1 + " (" + s1.type() + ") vs Reg " + reg2 + " (" + s2.type() + ").");
                return;
            }
            // GEÄNDERT: simulation wird übergeben
            performComparison(v1, v2, simulation);

        } else {
            // GEÄNDERT: simulation wird übergeben
            performComparison(v1, v2, simulation);
        }
    }

    // GEÄNDERT: Methode akzeptiert jetzt die Simulation als Parameter
    private void performComparison(int v1, int v2, Simulation simulation) {
        boolean conditionMet = false;
        int opcodeValue = this.fullOpcodeId & Config.VALUE_MASK;

        if (opcodeValue == ID && v1 == v2) conditionMet = true;
        if (opcodeValue == ID_LT && v1 < v2) conditionMet = true;
        if (opcodeValue == ID_GT && v1 > v2) conditionMet = true;

        boolean conditionFailed = !conditionMet;
        if (conditionFailed) {
            organism.skipNextInstruction(simulation.getWorld());
        }
    }
}