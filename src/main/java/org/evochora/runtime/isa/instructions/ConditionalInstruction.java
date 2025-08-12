package org.evochora.runtime.isa.instructions;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblerOutput;
import org.evochora.compiler.internal.legacy.NumericParser;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class ConditionalInstruction extends Instruction {

    public ConditionalInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            String opName = getName();
            if (opName.startsWith("IFM")) {
                List<Operand> operands = resolveOperands(simulation.getWorld());
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, simulation.getWorld());
                int ownerId = simulation.getWorld().getOwnerId(targetCoordinate);
                if (ownerId != organism.getId()) {
                    organism.skipNextInstruction(simulation.getWorld());
                }
                return;
            }
            List<Operand> operands = resolveOperands(simulation.getWorld());
            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for conditional operation.");
                return;
            }

            Operand op1 = operands.get(0);
            Operand op2 = operands.get(1);
            boolean conditionMet = false;


            if (opName.startsWith("IFT")) { // Type comparison
                int type1 = (op1.value() instanceof Integer i) ? org.evochora.runtime.model.Molecule.fromInt(i).type() : -1; // -1 for vectors
                int type2 = (op2.value() instanceof Integer i) ? Molecule.fromInt(i).type() : -1;
                conditionMet = (type1 == type2);
            } else { // Value comparison
                if (op1.value() instanceof int[] v1 && op2.value() instanceof int[] v2) {
                    conditionMet = Arrays.equals(v1, v2);
                } else if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                    Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);
                    Molecule s2 = org.evochora.runtime.model.Molecule.fromInt(i2);
                    if (Config.STRICT_TYPING && s1.type() != s2.type()) {
                        // Condition is false if types don't match in strict mode
                    } else {
                        int val1 = s1.toScalarValue();
                        int val2 = s2.toScalarValue();
                        switch (opName) {
                            case "IFR", "IFI", "IFS" -> conditionMet = (val1 == val2);
                            case "GTR", "GTI", "GTS" -> conditionMet = (val1 > val2);
                            case "LTR", "LTI", "LTS" -> conditionMet = (val1 < val2);
                            default -> organism.instructionFailed("Unknown conditional operation: " + opName);
                        }
                    }
                } else {
                    organism.instructionFailed("Mismatched operand types for comparison.");
                }
            }

            if (!conditionMet) {
                organism.skipNextInstruction(simulation.getWorld());
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during conditional operation.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getMolecule(organism.getIp()).toInt();
        return new ConditionalInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();

        if (name.endsWith("R") && !name.equals("IFMR")) { // IFR, GTR, LTR, IFTR
            if (args.length != 2) throw new IllegalArgumentException(name + " expects 2 register arguments.");
            Integer reg1 = resolveRegToken(args[0], registerMap);
            Integer reg2 = resolveRegToken(args[1], registerMap);
            if (reg1 == null || reg2 == null) throw new IllegalArgumentException("Invalid register for " + name);
            return new AssemblerOutput.CodeSequence(List.of(new Molecule(Config.TYPE_DATA, reg1).toInt(), new Molecule(Config.TYPE_DATA, reg2).toInt()));
        } else if (name.endsWith("I") && !name.equals("IFMI")) { // IFI, GTI, LTI, IFTI
            if (args.length != 2) throw new IllegalArgumentException(name + " expects a register and an immediate value.");
            Integer reg1 = resolveRegToken(args[0], registerMap);
            if (reg1 == null) throw new IllegalArgumentException("Invalid register for " + name);

            String[] literalParts = args[1].split(":");
            if (literalParts.length != 2) throw new IllegalArgumentException("Immediate argument must be in TYPE:VALUE format.");
            int type = getTypeFromString(literalParts[0]);
            int value = NumericParser.parseInt(literalParts[1]);
            return new AssemblerOutput.CodeSequence(List.of(new Molecule(Config.TYPE_DATA, reg1).toInt(), new Molecule(type, value).toInt()));
        } else if (name.endsWith("S") && !name.equals("IFMS")) { // IFS, GTS, LTS, IFTS
            if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
            return new AssemblerOutput.CodeSequence(List.of());
        } else if (name.equals("IFMR")) {
            if (args.length != 1) throw new IllegalArgumentException("IFMR expects 1 register argument.");
            Integer reg = resolveRegToken(args[0], registerMap);
            if (reg == null) throw new IllegalArgumentException("Invalid register for IFMR.");
            return new AssemblerOutput.CodeSequence(List.of(new Molecule(Config.TYPE_DATA, reg).toInt()));
        } else if (name.equals("IFMI")) {
            if (args.length != 1) throw new IllegalArgumentException("IFMI expects 1 vector argument.");
            String[] comps = args[0].split("\\|");
            if (comps.length != Config.WORLD_DIMENSIONS)
                throw new IllegalArgumentException("Invalid vector dimensionality for IFMI");
            List<Integer> machineCode = new ArrayList<>();
            for (String c : comps) {
                int v = NumericParser.parseInt(c.strip());
                machineCode.add(new Molecule(Config.TYPE_DATA, v).toInt());
            }
            return new AssemblerOutput.CodeSequence(machineCode);
        } else if (name.equals("IFMS")) {
            if (args.length != 0) throw new IllegalArgumentException("IFMS expects no arguments.");
            return new AssemblerOutput.CodeSequence(List.of());
        }


        throw new IllegalArgumentException("Cannot assemble unknown conditional instruction variant: " + name);
    }

    private static int getTypeFromString(String typeName) {
        return switch (typeName.toUpperCase()) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new IllegalArgumentException("Unknown type for literal: " + typeName);
        };
    }
}