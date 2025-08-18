package org.evochora.compiler.internal.legacy;

import org.evochora.compiler.internal.i18n.Messages;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 of the assembler: Resolves all jump and vector placeholders (linker).
 * Fills the remaining gaps in the machine code.
 */
public class PlaceholderResolver {
    private final String programName;
    private final Map<int[], Integer> machineCodeLayout;
    private final Map<String, Integer> labelMap;
    private final Map<Integer, int[]> linearAddressToCoordMap;

    // Internal data structures for placeholders
    public record JumpPlaceholder(int linearAddress, String labelName, AnnotatedLine line) {}
    public record VectorPlaceholder(int linearAddress, String labelName, int registerId, AnnotatedLine line) {}

    public PlaceholderResolver(String programName, Map<int[], Integer> machineCodeLayout, Map<String, Integer> labelMap, Map<Integer, int[]> linearAddressToCoordMap) {
        this.programName = programName;
        this.machineCodeLayout = machineCodeLayout;
        this.labelMap = labelMap;
        this.linearAddressToCoordMap = linearAddressToCoordMap;
    }

    public void resolve(List<JumpPlaceholder> jumpPlaceholders, List<VectorPlaceholder> vectorPlaceholders) {
        resolveJumps(jumpPlaceholders);
        resolveVectors(vectorPlaceholders);
    }

    private void resolveJumps(List<JumpPlaceholder> placeholders) {
        for (JumpPlaceholder placeholder : placeholders) {
            int jumpOpcodeAddress = placeholder.linearAddress();
            String targetLabel = placeholder.labelName();

            int[] jumpOpcodeCoord = linearAddressToCoordMap.get(jumpOpcodeAddress);
            if (jumpOpcodeCoord == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), Messages.get("placeholderResolver.jumpInstructionCoordinateNotFound"), placeholder.line().content());
            }

            Integer targetLabelAddress = labelMap.get(targetLabel.toUpperCase());
            if (targetLabelAddress == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), Messages.get("placeholderResolver.unknownLabelForJump", targetLabel), placeholder.line().content());
            }

            int[] targetCoord = linearAddressToCoordMap.get(targetLabelAddress);
            if (targetCoord == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), Messages.get("placeholderResolver.targetLabelCoordinateNotFound", targetLabel), placeholder.line().content());
            }

            // Calculate the relative delta
            int[] delta = new int[Config.WORLD_DIMENSIONS];
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                delta[i] = targetCoord[i] - jumpOpcodeCoord[i];
            }

            // Write the delta values to the machine code layout using the real coordinates
            // derived from linear addresses (respects .DIR / current DV)
            for (int i = 0; i < delta.length; i++) {
                int argLinearAddr = jumpOpcodeAddress + 1 + i;
                int[] argCoord = linearAddressToCoordMap.get(argLinearAddr);
                if (argCoord == null) {
                    throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(),
                            Messages.get("placeholderResolver.jumpArgumentCoordinateNotFound"), placeholder.line().content());
                }
                machineCodeLayout.put(Arrays.copyOf(argCoord, argCoord.length), new Molecule(Config.TYPE_DATA, delta[i]).toInt());
            }
        }
    }

    private void resolveVectors(List<VectorPlaceholder> placeholders) {
        for (VectorPlaceholder placeholder : placeholders) {
            int opcodeAddress = placeholder.linearAddress();
            String targetLabel = placeholder.labelName();

            Integer targetLabelAddress = labelMap.get(targetLabel.toUpperCase());
            if (targetLabelAddress == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), Messages.get("placeholderResolver.unknownLabelForVector", targetLabel), placeholder.line().content());
            }

            int[] targetCoord = linearAddressToCoordMap.get(targetLabelAddress);
            if (targetCoord == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), Messages.get("placeholderResolver.targetLabelCoordinateNotFound", targetLabel), placeholder.line().content());
            }

            // Das erste Argument ist das Register, es beginnt bei `opcodeAddress + 1`.
            // Die Vektorkomponenten beginnen bei `opcodeAddress + 2`.
            for (int i = 0; i < targetCoord.length; i++) {
                int argLinearAddr = opcodeAddress + 2 + i;
                int[] argCoord = linearAddressToCoordMap.get(argLinearAddr);
                if (argCoord == null) {
                    // Diese Exception sollte nie auftreten, wenn der PassManager korrekt arbeitet.
                    throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(),
                            "Internal error: Coordinate for vector argument not found.", placeholder.line().content());
                }
                machineCodeLayout.put(Arrays.copyOf(argCoord, argCoord.length), new Molecule(Config.TYPE_DATA, targetCoord[i]).toInt());
            }
        }
    }
}