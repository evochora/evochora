// src/main/java/org/evochora/organism/IfrInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class IfrInstruction extends Instruction {

    // Wir verwenden die ursprünglichen IDs der mehrdeutigen IF-Befehle wieder
    public static final int ID_IFR = 7;
    public static final int ID_LTR = 8;
    public static final int ID_GTR = 9;

    private final int reg1;
    private final int reg2;
    private final int fullOpcodeId;

    public IfrInstruction(Organism o, int r1, int r2, int fullOpcodeId) {
        super(o);
        this.reg1 = r1;
        this.reg2 = r2;
        this.fullOpcodeId = fullOpcodeId;
    }

    static {
        // Registriere die drei Register-Register-Vergleichsbefehle
        Instruction.registerInstruction(IfrInstruction.class, ID_IFR, "IFR", 3, IfrInstruction::plan, IfrInstruction::assemble);
        Instruction.registerInstruction(IfrInstruction.class, ID_LTR, "LTR", 3, IfrInstruction::plan, IfrInstruction::assemble);
        Instruction.registerInstruction(IfrInstruction.class, ID_GTR, "GTR", 3, IfrInstruction::plan, IfrInstruction::assemble);

        Instruction.registerArgumentTypes(ID_IFR, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
        Instruction.registerArgumentTypes(ID_LTR, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
        Instruction.registerArgumentTypes(ID_GTR, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
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
        if (argIndex == 0 || argIndex == 1) return ArgumentType.REGISTER;
        throw new IllegalArgumentException("Ungültiger Argumentindex für " + getName() + ": " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();

        // Lese das erste Argument
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();

        // KORREKTUR: Lese das zweite Argument explizit von der Position nach dem ersten.
        int[] nextIp = organism.getNextInstructionPosition(result1.nextIp(), world, organism.getDvBeforeFetch());
        Organism.FetchResult result2 = organism.fetchArgument(nextIp, world);
        int r2 = result2.value();

        return new IfrInstruction(organism, r1, r2, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException( "IFR/LTR/GTR erwarten genau 2 Register-Argumente: %REG1 %REG2");

        Integer reg1Id = registerMap.get(args[0].toUpperCase());
        Integer reg2Id = registerMap.get(args[1].toUpperCase());

        if (reg1Id == null || reg2Id == null) {
            throw new IllegalArgumentException(String.format("Ungültiges Register-Argument. Reg1: '%s', Reg2: '%s'", args[0], args[1]));
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

        // KORREKTUR: Vektor-Vergleich erlauben
        if (val1Obj instanceof int[] v1 && val2Obj instanceof int[] v2) {
            if (Arrays.equals(v1, v2)) {
                performComparison(1, 1); // Simuliert Gleichheit
            } else {
                performComparison(1, 2); // Simuliert Ungleichheit
            }
            return;
        }

        if (val1Obj instanceof int[] || val2Obj instanceof int[]) {
            organism.instructionFailed(getName() + ": Typen-Mischmasch (Vektor vs. Skalar) ist nicht erlaubt. Register (" + reg1 + ", " + reg2 + ").");
            return;
        }

        if (!(val1Obj instanceof Integer v1Raw) || !(val2Obj instanceof Integer v2Raw)) {
            organism.instructionFailed(getName() + ": Ungültige oder null-Typen in Registern (" + reg1 + ", " + reg2 + ").");
            return;
        }

        Symbol s1 = Symbol.fromInt(v1Raw);
        Symbol s2 = Symbol.fromInt(v2Raw);

        if (Config.STRICT_TYPING) {
            if (s1.type() != s2.type()) {
                organism.instructionFailed(getName() + ": Registertypen müssen im strikten Modus übereinstimmen. Reg " + reg1 + " (" + s1.type() + ") vs Reg " + reg2 + " (" + s2.type() + ").");
                return;
            }
        }

        performComparison(s1.toScalarValue(), s2.toScalarValue());
    }

    private void performComparison(int v1, int v2) {
        boolean conditionMet = false;
        int opcodeValue = this.fullOpcodeId & Config.VALUE_MASK;

        if (opcodeValue == ID_IFR && v1 == v2) conditionMet = true;
        if (opcodeValue == ID_LTR && v1 < v2) conditionMet = true;
        if (opcodeValue == ID_GTR && v1 > v2) conditionMet = true;

        boolean conditionFailed = !conditionMet;
        if (conditionFailed) {
            organism.skipNextInstruction(organism.getSimulation().getWorld());
        }
    }
}