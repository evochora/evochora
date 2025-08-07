package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.world.Symbol;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 des Assemblers: Löst alle Sprung- und Vektor-Platzhalter auf (Linker).
 * Füllt die verbleibenden Lücken im Maschinencode.
 */
public class PlaceholderResolver {
    private final String programName;
    private final Map<int[], Integer> machineCodeLayout;
    private final Map<String, Integer> labelMap;
    private final Map<Integer, int[]> linearAddressToCoordMap;

    // Interne Datenstrukturen für die Platzhalter
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
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), "Interner Fehler: Koordinate für Sprungbefehl nicht gefunden.", placeholder.line().content());
            }

            Integer targetLabelAddress = labelMap.get(targetLabel.toUpperCase());
            if (targetLabelAddress == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), "Unbekanntes Label für Sprungbefehl: " + targetLabel, placeholder.line().content());
            }

            int[] targetCoord = linearAddressToCoordMap.get(targetLabelAddress);
            if (targetCoord == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), "Interner Fehler: Koordinate für Ziel-Label nicht gefunden: " + targetLabel, placeholder.line().content());
            }

            // Berechne das relative Delta
            int[] delta = new int[Config.WORLD_DIMENSIONS];
            for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                delta[i] = targetCoord[i] - jumpOpcodeCoord[i];
            }

            // Schreibe die Delta-Werte in das machineCodeLayout
            int[] argPos = Arrays.copyOf(jumpOpcodeCoord, jumpOpcodeCoord.length);
            // Annahme: dv ist [1, 0] für die Platzhalter-Auflösung. Dies muss ggf. angepasst werden,
            // wenn .DIR innerhalb von Sprüngen eine Rolle spielt. Für den Moment ist dies eine Vereinfachung.
            argPos[0]++;

            for (int component : delta) {
                machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, component).toInt());
                argPos[0]++;
            }
        }
    }

    private void resolveVectors(List<VectorPlaceholder> placeholders) {
        for (VectorPlaceholder placeholder : placeholders) {
            int opcodeAddress = placeholder.linearAddress();
            String targetLabel = placeholder.labelName();

            Integer targetLabelAddress = labelMap.get(targetLabel.toUpperCase());
            if (targetLabelAddress == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), "Unbekanntes Label für Vektor-Zuweisung: " + targetLabel, placeholder.line().content());
            }

            int[] targetCoord = linearAddressToCoordMap.get(targetLabelAddress);
            if (targetCoord == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), "Interner Fehler: Koordinate für Ziel-Label nicht gefunden: " + targetLabel, placeholder.line().content());
            }

            int[] opcodeCoord = linearAddressToCoordMap.get(opcodeAddress);
            if (opcodeCoord == null) {
                throw new AssemblerException(programName, placeholder.line().originalFileName(), placeholder.line().originalLineNumber(), "Interner Fehler: Koordinate für Vektor-Befehl nicht gefunden.", placeholder.line().content());
            }

            // Schreibe die Vektor-Werte in das machineCodeLayout
            int[] argPos = Arrays.copyOf(opcodeCoord, opcodeCoord.length);
            argPos[0]++; // Zum Register-Argument
            argPos[0]++; // Zur ersten Vektor-Komponente

            for (int component : targetCoord) {
                machineCodeLayout.put(Arrays.copyOf(argPos, argPos.length), new Symbol(Config.TYPE_DATA, component).toInt());
                argPos[0]++;
            }
        }
    }
}