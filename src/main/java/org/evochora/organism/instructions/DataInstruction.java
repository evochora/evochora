package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class DataInstruction extends Instruction {

    public DataInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            List<Operand> operands = resolveOperands(simulation.getWorld());
            String opName = getName();

            switch (opName) {
                case "SETI":
                case "SETV": {
                    if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + opName); return; }
                    Operand dest = operands.get(0);
                    Operand source = operands.get(1);
                    writeOperand(dest.rawSourceId(), source.value());
                    break;
                }
                case "SETR": {
                    if (operands.size() != 2) { organism.instructionFailed("Invalid operands for SETR"); return; }
                    Operand dest = operands.get(0);
                    Operand source = operands.get(1);
                    writeOperand(dest.rawSourceId(), source.value());
                    break;
                }
                case "PUSH": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for PUSH"); return; }
                    if (organism.getDataStack().size() >= Config.DS_MAX_DEPTH) { organism.instructionFailed("Stack Overflow"); return; }
                    organism.getDataStack().push(operands.get(0).value());
                    break;
                }
                case "POP": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for POP"); return; }
                    Object value = organism.getDataStack().pop();
                    writeOperand(operands.get(0).rawSourceId(), value);
                    break;
                }
                case "PUSI": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for PUSI"); return; }
                    if (organism.getDataStack().size() >= Config.DS_MAX_DEPTH) { organism.instructionFailed("Stack Overflow"); return; }
                    organism.getDataStack().push(operands.get(0).value());
                    break;
                }
                default:
                    organism.instructionFailed("Unknown data instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during data operation.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new DataInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();

        switch (name) {
            case "SETI": {
                if (args.length != 2) throw new IllegalArgumentException("SETI expects a register and a literal.");
                Integer reg = resolveRegToken(args[0], registerMap);
                if (reg == null) throw new IllegalArgumentException("Invalid register for SETI.");
                String[] literalParts = args[1].split(":");
                if (literalParts.length != 2) throw new IllegalArgumentException("Literal must be in TYPE:VALUE format.");
                int type = getTypeFromString(literalParts[0]);
                int value = org.evochora.assembler.NumericParser.parseInt(literalParts[1]);
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg).toInt(), new Symbol(type, value).toInt()));
            }
            case "SETR": {
                if (args.length != 2) throw new IllegalArgumentException("SETR expects two register arguments.");
                Integer dest = resolveRegToken(args[0], registerMap);
                Integer src = resolveRegToken(args[1], registerMap);
                if (dest == null || src == null) throw new IllegalArgumentException("Invalid register for SETR.");
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, dest).toInt(), new Symbol(Config.TYPE_DATA, src).toInt()));
            }
            case "SETV": {
                if (args.length != 2) throw new IllegalArgumentException("SETV expects a register and a vector/label.");
                Integer reg = resolveRegToken(args[0], registerMap);
                if (reg == null) throw new IllegalArgumentException("Invalid register for SETV.");
                String vectorArg = args[1];
                if (!vectorArg.contains("|") && labelMap.containsKey(vectorArg.toUpperCase())) {
                    return new AssemblerOutput.LabelToVectorRequest(vectorArg, reg);
                }
                String[] comps = vectorArg.split("\\|");
                if (comps.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimensionality.");
                List<Integer> machineCode = new ArrayList<>();
                machineCode.add(new Symbol(Config.TYPE_DATA, reg).toInt());
                for (String c : comps) {
                    int v = org.evochora.assembler.NumericParser.parseInt(c.strip());
                    machineCode.add(new Symbol(Config.TYPE_DATA, v).toInt());
                }
                return new AssemblerOutput.CodeSequence(machineCode);
            }
            case "PUSH": {
                if (args.length != 1) throw new IllegalArgumentException("PUSH expects one register argument.");
                Integer reg = resolveRegToken(args[0], registerMap);
                if (reg == null) throw new IllegalArgumentException("Invalid register for PUSH.");
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg).toInt()));
            }
            case "POP": {
                if (args.length != 1) throw new IllegalArgumentException("POP expects one register argument.");
                Integer reg = resolveRegToken(args[0], registerMap);
                if (reg == null) throw new IllegalArgumentException("Invalid register for POP.");
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg).toInt()));
            }
            case "PUSI": {
                if (args.length != 1) throw new IllegalArgumentException("PUSI expects one literal argument.");
                String[] literalParts = args[0].split(":");
                if (literalParts.length != 2) throw new IllegalArgumentException("Literal must be in TYPE:VALUE format.");
                int type = getTypeFromString(literalParts[0]);
                int value = org.evochora.assembler.NumericParser.parseInt(literalParts[1]);
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(type, value).toInt()));
            }
        }
        throw new IllegalArgumentException("Cannot assemble unknown data instruction variant: " + name);
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
