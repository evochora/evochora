// src/main/java/org/evochora/assembler/Disassembler.java
package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

    public class Disassembler {

        public DisassembledInstruction disassemble(ProgramMetadata metadata, int[] coord, int[] currentDv, World world) {
            Map<List<Integer>, Integer> relativeCoordToLinearAddress = metadata.relativeCoordToLinearAddress();
            Map<Integer, int[]> linearAddressToRelativeCoord = metadata.linearAddressToRelativeCoord();
            Map<Integer, String> registerIdToName = metadata.registerIdToName();
            Map<Integer, String> labelAddressToName = metadata.labelAddressToName();

            Integer linearAddress = relativeCoordToLinearAddress != null ? relativeCoordToLinearAddress.get(Arrays.stream(coord).boxed().collect(Collectors.toList())) : null;

            Symbol symbol = world.getSymbol(coord);
            String instructionType = getInstructionTypeString(symbol);
            String opcodeName = "N/A";
            List<DisassembledArgument> arguments = new ArrayList<>();
            String resolvedTargetCoordinate = null;

            if (symbol.type() == Config.TYPE_CODE) {
                int opcodeFullId = symbol.toInt();
                opcodeName = Instruction.getInstructionNameById(opcodeFullId);

                if (opcodeName != null && !opcodeName.startsWith("UNKNOWN")) {
                    int instructionLength = Instruction.getInstructionLengthById(opcodeFullId);
                    int[] assemblyDv = currentDv;
                    int[] currentArgCoord = Arrays.copyOf(coord, coord.length);

                    for (int i = 0; i < instructionLength - 1; i++) {
                        for(int d=0; d<Config.WORLD_DIMENSIONS; d++) {
                            currentArgCoord[d] += assemblyDv[d];
                        }
                        currentArgCoord = world.getNormalizedCoordinate(currentArgCoord);

                        Symbol argSymbol = world.getSymbol(currentArgCoord);
                        String argResolvedValue;

                        // KORRIGIERT: Verwende die neue statische Methode, um den ArgumentType zu bekommen
                        ArgumentType argType = Instruction.getArgumentTypeFor(opcodeFullId, i);
                        int rawValue = argSymbol.value();

                        if (argType == ArgumentType.REGISTER && registerIdToName != null && registerIdToName.containsKey(rawValue)) {
                            argResolvedValue = registerIdToName.get(rawValue);
                        } else if (opcodeName.equals("JMPR") && linearAddress != null && linearAddressToRelativeCoord != null && relativeCoordToLinearAddress != null) {
                            int jumpToLinearAddress = linearAddress + 1;
                            int[] targetCoord = new int[Config.WORLD_DIMENSIONS];
                            for(int d=0; d<Config.WORLD_DIMENSIONS; d++) {
                                targetCoord[d] = coord[d] + rawValue;
                            }
                            targetCoord = world.getNormalizedCoordinate(targetCoord);
                            Integer targetLinearAddress = relativeCoordToLinearAddress.get(Arrays.stream(targetCoord).boxed().collect(Collectors.toList()));

                            if(targetLinearAddress != null && labelAddressToName.containsKey(targetLinearAddress)) {
                                argResolvedValue = labelAddressToName.get(targetLinearAddress);
                                argType = ArgumentType.LABEL;
                            } else {
                                argResolvedValue = getInstructionTypeString(argSymbol) + ":" + argSymbol.toScalarValue();
                            }
                        } else {
                            String typeStr = getInstructionTypeString(argSymbol);
                            argResolvedValue = typeStr + ":" + argSymbol.toScalarValue();
                        }

                        arguments.add(new DisassembledArgument(rawValue, argResolvedValue, argType));
                    }
                } else {
                    opcodeName = "UNKNOWN_OP";
                    arguments.add(new DisassembledArgument(symbol.value(), String.valueOf(symbol.value()), ArgumentType.LITERAL));
                }
            } else {
                String typeStr = getInstructionTypeString(symbol);
                String resolvedValue = typeStr + ":" + symbol.value();
                arguments.add(new DisassembledArgument(symbol.value(), resolvedValue, ArgumentType.LITERAL));
            }

            return new DisassembledInstruction(opcodeName, arguments, resolvedTargetCoordinate, instructionType);
        }

        public DisassembledInstruction disassembleGeneric(int[] coord, World world) {
        Symbol symbol = world.getSymbol(coord);
        String instructionType = getInstructionTypeString(symbol);
        String opcodeName = "N/A";
        List<DisassembledArgument> arguments = new ArrayList<>();

        if (symbol.type() == Config.TYPE_CODE) {
            int opcodeFullId = symbol.toInt();
            opcodeName = Instruction.getInstructionNameById(opcodeFullId);

            if (opcodeName != null && !opcodeName.startsWith("UNKNOWN")) {
                int instructionLength = Instruction.getInstructionLengthById(opcodeFullId);
                int[] tempArgCoord = Arrays.copyOf(coord, coord.length);
                int[] defaultDv = new int[Config.WORLD_DIMENSIONS];
                defaultDv[0] = 1;

                for (int i = 0; i < instructionLength - 1; i++) {
                    for(int j=0; j<Config.WORLD_DIMENSIONS; j++) {
                        tempArgCoord[j] += defaultDv[j];
                    }
                    tempArgCoord = world.getNormalizedCoordinate(tempArgCoord);
                    Symbol argSymbol = world.getSymbol(tempArgCoord);

                    String typeStr = getInstructionTypeString(argSymbol);
                    String resolvedValue = typeStr + ":" + argSymbol.value();
                    arguments.add(new DisassembledArgument(argSymbol.value(), resolvedValue, ArgumentType.LITERAL));
                }
            } else {
                opcodeName = "UNKNOWN_OP";
            }
        }

        return new DisassembledInstruction(opcodeName, arguments, null, instructionType);
    }

    private String getInstructionTypeString(Symbol symbol) {
        return switch (symbol.type()) {
            case Config.TYPE_CODE -> "CODE";
            case Config.TYPE_DATA -> "DATA";
            case Config.TYPE_ENERGY -> "ENERGY";
            case Config.TYPE_STRUCTURE -> "STRUCTURE";
            default -> "UNKN";
        };
    }
}