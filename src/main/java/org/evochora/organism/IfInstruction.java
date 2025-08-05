// src/main/java/org/evochora/organism/IfInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import org.evochora.world.Symbol;

import java.util.ArrayList;
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

        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
        Instruction.registerArgumentTypes(ID_LT, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
        Instruction.registerArgumentTypes(ID_GT, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
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

    // Correction starts here
    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("IF-Befehle erwarten 2 Argumente: %REG1 %REG2 oder %REG1 TYPE:WERT");

        Integer reg1Id = registerMap.get(args[0].toUpperCase());
        if (reg1Id == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument für IF: " + args[0]);
        }

        Integer reg2Id = null;
        Integer literalValue = null;

        if (args[1].contains(":")) {
            String[] literalParts = args[1].split(":");
            if (literalParts.length != 2) {
                throw new IllegalArgumentException("IF-Literal muss das Format TYPE:WERT haben: " + args[1]);
            }
            String typeName = literalParts[0].toUpperCase();
            int value = Integer.parseInt(literalParts[1]);
            int type = switch (typeName) {
                case "CODE" -> Config.TYPE_CODE;
                case "DATA" -> Config.TYPE_DATA;
                case "ENERGY" -> Config.TYPE_ENERGY;
                case "STRUCTURE" -> Config.TYPE_STRUCTURE;
                default -> throw new IllegalArgumentException("Unbekannter Typ für IF-Literal: " + typeName);
            };
            literalValue = new Symbol(type, value).toInt();
        } else {
            reg2Id = registerMap.get(args[1].toUpperCase());
            if (reg2Id == null) {
                throw new IllegalArgumentException("Ungültiges Register-Argument für IF: " + args[1]);
            }
        }

        // Pass the first argument as a register, and the second as either a register or a literal
        List<Integer> machineCode = new ArrayList<>();
        machineCode.add(new Symbol(Config.TYPE_DATA, reg1Id).toInt());
        if (reg2Id != null) {
            machineCode.add(new Symbol(Config.TYPE_DATA, reg2Id).toInt());
        } else if (literalValue != null) {
            machineCode.add(literalValue);
        } else {
            throw new IllegalStateException("Unerwarteter Zustand im IF-Assembler.");
        }

        return new AssemblerOutput.CodeSequence(machineCode);
    }
    // Correction ends here

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
            performComparison(v1, v2, simulation);

        } else {
            performComparison(v1, v2, simulation);
        }
    }

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