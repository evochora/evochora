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
                    ArgumentType argType = ArgumentType.LITERAL;

                    try {
                        Instruction tempInstruction = Instruction.getInstructionClassById(opcodeFullId).getDeclaredConstructor(Organism.class).newInstance((Object) null);
                        argType = tempInstruction.getArgumentType(i);
                    } catch (Exception e) {
                        // Ignorieren, Fallback auf LITERAL
                    }

                    // --- HIER IST DIE KORREKTUR ---
                    // Wir prüfen explizit, ob es sich um ein Register handelt UND ob wir einen Namen dafür haben.
                    if (argType == ArgumentType.REGISTER && registerIdToName != null && registerIdToName.containsKey(argSymbol.value())) {
                        argResolvedValue = registerIdToName.get(argSymbol.value());
                    } else {
                        // Für alle anderen Fälle (Literale, Koordinaten, oder Register ohne Namen)
                        // zeigen wir den Typ und den Wert an.
                        String typeStr = getInstructionTypeString(argSymbol);
                        argResolvedValue = typeStr + ":" + argSymbol.toScalarValue(); // .toScalarValue() für korrekte negative Zahlen
                    }

                    // Wir speichern den rohen Wert (z.B. die Registernummer 0) und den aufbereiteten String (z.B. "%VEC_UP")
                    arguments.add(new DisassembledArgument(argSymbol.value(), argResolvedValue, argType));
                }

                if (opcodeName.equals("JMPR") && !arguments.isEmpty()) {
                    // Diese Logik kann vereinfacht werden, da die Argumente bereits formatiert sind
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

    // ... Der Rest der Klasse bleibt unverändert ...
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