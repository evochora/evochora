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
import java.util.NoSuchElementException;

public class BitwiseInstruction extends Instruction {

    public BitwiseInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            List<Operand> operands = resolveOperands(simulation.getWorld());
            String opName = getName();

            // Handle NOT separately as it has only one operand
            if (opName.contains("NOT")) {
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for NOT operation.");
                    return;
                }
                Operand op1 = operands.get(0);
                if (op1.value() instanceof Integer i1) {
                    Symbol s1 = Symbol.fromInt(i1);
                    int resultValue = ~s1.toScalarValue();
                    Object result = new Symbol(s1.type(), resultValue).toInt();

                    if (op1.rawSourceId() != -1) {
                        writeOperand(op1.rawSourceId(), result);
                    } else {
                        organism.getDataStack().push(result);
                    }
                } else {
                    organism.instructionFailed("NOT operations only support scalar values.");
                }
                return;
            }

            // All other bitwise operations have two operands
            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for bitwise operation.");
                return;
            }

            Operand op1 = operands.get(0);
            Operand op2 = operands.get(1);

            if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                Symbol s1 = Symbol.fromInt(i1);
                Symbol s2;
                if (op2.rawSourceId() == -1) { // Immediate
                    s2 = new Symbol(s1.type(), i2);
                } else { // Register
                    s2 = Symbol.fromInt(i2);
                }

                if (Config.STRICT_TYPING && s1.type() != s2.type()) {
                    organism.instructionFailed("Operand types must match in strict mode for bitwise operations.");
                    return;
                }

                // For shifts, the second operand must be DATA type
                if (opName.contains("SH") && s2.type() != Config.TYPE_DATA) {
                    organism.instructionFailed("Shift amount must be of type DATA.");
                    return;
                }

                long scalarResult;
                String baseOp = opName.substring(0, opName.length() - 1); // "ANDR" -> "AND"

                switch (baseOp) {
                    case "NAD" -> scalarResult = ~(s1.toScalarValue() & s2.toScalarValue());
                    case "AND" -> scalarResult = s1.toScalarValue() & s2.toScalarValue();
                    case "OR" -> scalarResult = s1.toScalarValue() | s2.toScalarValue();
                    case "XOR" -> scalarResult = s1.toScalarValue() ^ s2.toScalarValue();
                    case "SHL" -> scalarResult = s1.toScalarValue() << s2.toScalarValue();
                    case "SHR" -> scalarResult = s1.toScalarValue() >> s2.toScalarValue();
                    default -> {
                        organism.instructionFailed("Unknown bitwise operation: " + opName);
                        return;
                    }
                }
                Object result = new Symbol(s1.type(), (int)scalarResult).toInt();

                if (op1.rawSourceId() != -1) {
                    writeOperand(op1.rawSourceId(), result);
                } else {
                    organism.getDataStack().push(result);
                }

            } else {
                organism.instructionFailed("Bitwise operations only support scalar values.");
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during bitwise operation.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new BitwiseInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();

        if (name.startsWith("NOT")) {
            if (name.endsWith("S")) { // NOTS
                if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
                return new AssemblerOutput.CodeSequence(List.of());
            } else { // NOT
                if (args.length != 1) throw new IllegalArgumentException(name + " expects 1 register argument.");
                Integer reg = resolveRegToken(args[0], registerMap);
                if (reg == null) throw new IllegalArgumentException("Invalid register for " + name);
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg).toInt()));
            }
        }

        if (name.endsWith("R")) {
            if (args.length != 2) throw new IllegalArgumentException(name + " expects 2 register arguments.");
            Integer reg1 = resolveRegToken(args[0], registerMap);
            Integer reg2 = resolveRegToken(args[1], registerMap);
            if (reg1 == null || reg2 == null) throw new IllegalArgumentException("Invalid register for " + name);
            return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg1).toInt(), new Symbol(Config.TYPE_DATA, reg2).toInt()));

        } else if (name.endsWith("I")) {
            if (args.length != 2) throw new IllegalArgumentException(name + " expects a register and an immediate value.");
            Integer reg1 = resolveRegToken(args[0], registerMap);
            if (reg1 == null) throw new IllegalArgumentException("Invalid register for " + name);

            String[] literalParts = args[1].split(":");
            if (literalParts.length != 2) throw new IllegalArgumentException("Immediate argument must be in TYPE:VALUE format.");
            String typeName = literalParts[0].toUpperCase();
            int value = Integer.parseInt(literalParts[1]);
            int type = switch (typeName) {
                case "CODE" -> Config.TYPE_CODE;
                case "DATA" -> Config.TYPE_DATA;
                case "ENERGY" -> Config.TYPE_ENERGY;
                case "STRUCTURE" -> Config.TYPE_STRUCTURE;
                default -> throw new IllegalArgumentException("Unknown type for literal: " + typeName);
            };
            return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg1).toInt(), new Symbol(type, value).toInt()));

        } else if (name.endsWith("S")) {
            if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
            return new AssemblerOutput.CodeSequence(List.of());
        }

        throw new IllegalArgumentException("Cannot assemble unknown instruction variant: " + name);
    }
}
