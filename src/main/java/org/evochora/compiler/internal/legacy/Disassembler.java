// src/main/java/org/evochora/assembler/Disassembler.java
package org.evochora.compiler.internal.legacy;

import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Disassembler {
    private static final boolean DEBUG_ANNOTATE_ADAPTERS = true;

    public DisassembledInstruction disassemble(ProgramMetadata metadata, int[] coord, int[] currentDv, Environment environment) {
        Map<List<Integer>, Integer> relativeCoordToLinearAddress = metadata.relativeCoordToLinearAddress();
        Map<Integer, int[]> linearAddressToRelativeCoord = metadata.linearAddressToCoord();
        Map<String, Integer> registerNameToId = metadata.registerMap();
        // Invert the map for efficient lookup
        Map<Integer, String> registerIdToName = registerNameToId.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        Map<Integer, String> labelAddressToName = metadata.labelAddressToName();

        Integer linearAddress = relativeCoordToLinearAddress != null ? relativeCoordToLinearAddress.get(Arrays.stream(coord).boxed().collect(Collectors.toList())) : null;

        Molecule molecule = environment.getMolecule(coord);
        String instructionType = getInstructionTypeString(molecule);
        String opcodeName = "N/A";
        List<DisassembledArgument> arguments = new ArrayList<>();
        String resolvedTargetCoordinate = null;

        if (molecule.type() == Config.TYPE_CODE) {
            int opcodeFullId = molecule.toInt();
            opcodeName = Instruction.getInstructionNameById(opcodeFullId);

            if (opcodeName != null && !opcodeName.startsWith("UNKNOWN")) {
                int instructionLength = Instruction.getInstructionLengthById(opcodeFullId);
                int[] assemblyDv = currentDv;
                int[] currentArgCoord = Arrays.copyOf(coord, coord.length);

                for (int i = 0; i < instructionLength - 1; i++) {
                    for(int d=0; d<Config.WORLD_DIMENSIONS; d++) {
                        currentArgCoord[d] += assemblyDv[d];
                    }
                    currentArgCoord = environment.getNormalizedCoordinate(currentArgCoord);

                    Molecule argMolecule = environment.getMolecule(currentArgCoord);
                    String argResolvedValue;

                    ArgumentType argType = Instruction.getArgumentTypeFor(opcodeFullId, i);
                    int rawValue = argMolecule.value();

                    if (argType == ArgumentType.REGISTER) {
                        // Bevorzugt den Alias aus den Metadaten, falls vorhanden
                        if (registerIdToName != null && registerIdToName.containsKey(rawValue)) {
                            argResolvedValue = registerIdToName.get(rawValue);
                        } else if (rawValue >= 2000) { // NEU: FPRs erkennen
                            argResolvedValue = "%FPR" + (rawValue - 2000);
                        } else if (rawValue >= 1000) {
                            argResolvedValue = "%PR" + (rawValue - 1000);
                        } else {
                            argResolvedValue = "%DR" + rawValue;
                        }
                    } else if (opcodeName.equals("JMPR") && linearAddress != null && linearAddressToRelativeCoord != null && relativeCoordToLinearAddress != null) {
                        int[] targetCoord = new int[Config.WORLD_DIMENSIONS];
                        for(int d=0; d<Config.WORLD_DIMENSIONS; d++) {
                            targetCoord[d] = coord[d] + rawValue;
                        }
                        targetCoord = environment.getNormalizedCoordinate(targetCoord);
                        Integer targetLinearAddress = relativeCoordToLinearAddress.get(Arrays.stream(targetCoord).boxed().collect(Collectors.toList()));

                        if(targetLinearAddress != null && labelAddressToName.containsKey(targetLinearAddress)) {
                            argResolvedValue = labelAddressToName.get(targetLinearAddress);
                            argType = ArgumentType.LABEL;
                        } else {
                            argResolvedValue = getInstructionTypeString(argMolecule) + ":" + argMolecule.toScalarValue();
                        }
                    } else {
                        String typeStr = getInstructionTypeString(argMolecule);
                        argResolvedValue = typeStr + ":" + argMolecule.toScalarValue();
                    }

                    arguments.add(new DisassembledArgument(rawValue, argResolvedValue, argType));
                }
            } else {
                opcodeName = "UNKNOWN_OP";
                arguments.add(new DisassembledArgument(molecule.value(), String.valueOf(molecule.value()), ArgumentType.LITERAL));
            }
        } else {
            String typeStr = getInstructionTypeString(molecule);
            String resolvedValue = typeStr + ":" + molecule.value();
            arguments.add(new DisassembledArgument(molecule.value(), resolvedValue, ArgumentType.LITERAL));
        }

        if (DEBUG_ANNOTATE_ADAPTERS && "SETR".equalsIgnoreCase(opcodeName) && arguments.size() == 2) {
            boolean bothRegisterArgs = arguments.stream().allMatch(a -> a.type() == ArgumentType.REGISTER);
            if (bothRegisterArgs) {
                int raw0 = arguments.get(0).rawValue();
                int raw1 = arguments.get(1).rawValue();
                boolean involvesPrOrFpr = (raw0 >= 1000) || (raw1 >= 1000); // PRs oder FPRs
                if (involvesPrOrFpr) {
                    opcodeName = opcodeName + " [adapter]";
                }
            }
        }

        return new DisassembledInstruction(opcodeName, arguments, resolvedTargetCoordinate, instructionType);
    }

    public DisassembledInstruction disassembleGeneric(int[] coord, Environment environment) {
        Molecule molecule = environment.getMolecule(coord);
        String instructionType = getInstructionTypeString(molecule);
        String opcodeName = "N/A";
        List<DisassembledArgument> arguments = new ArrayList<>();

        if (molecule.type() == Config.TYPE_CODE) {
            int opcodeFullId = molecule.toInt();
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
                    tempArgCoord = environment.getNormalizedCoordinate(tempArgCoord);
                    Molecule argMolecule = environment.getMolecule(tempArgCoord);

                    String typeStr = getInstructionTypeString(argMolecule);
                    String resolvedValue = typeStr + ":" + argMolecule.value();
                    arguments.add(new DisassembledArgument(argMolecule.value(), resolvedValue, ArgumentType.LITERAL));
                }
            } else {
                opcodeName = "UNKNOWN_OP";
            }
        }

        return new DisassembledInstruction(opcodeName, arguments, null, instructionType);
    }

    private String getInstructionTypeString(Molecule molecule) {
        return switch (molecule.type()) {
            case Config.TYPE_CODE -> "CODE";
            case Config.TYPE_DATA -> "DATA";
            case Config.TYPE_ENERGY -> "ENERGY";
            case Config.TYPE_STRUCTURE -> "STRUCTURE";
            default -> "UNKN";
        };
    }
}
