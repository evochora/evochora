package org.evochora.compiler.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Repräsentiert das unveränderliche Ergebnis eines Kompilierungsvorgangs.
 * <p>
 * Dieses Objekt bündelt alle Informationen, die zur Ausführung und zum Debugging
 * eines Programms notwendig sind, in einer einzigen, sauberen Datenstruktur.
 * Es dient als zentraler Vertrag zwischen dem Compiler und der Laufzeitumgebung (VM).
 * <p>
 * TODO: [Phase 2 Refactoring] Dieses Artefakt ist der "saubere" Zielzustand.
 *  Der Legacy-Compiler (PassManager etc.) erzeugt aktuell noch die alte ProgramMetadata-Klasse.
 *  In Phase 2 werden wir einen "LegacyCompilerAdapter" erstellen, der die alte ProgramMetadata
 *  in dieses neue, saubere ProgramArtifact umwandelt. In Phase 4 wird der neue Compiler
 *  dieses Artefakt dann direkt erzeugen.
 *
 * @param programId                   Eine eindeutige ID, die aus dem Hash des Maschinencodes generiert wird.
 * @param machineCodeLayout           Die Hauptausgabe: Eine Map von relativen Koordinaten zu den dort platzierten Maschinenwörtern.
 * @param initialWorldObjects         Objekte (z.B. aus .PLACE), die vor der Ausführung in die Welt platziert werden müssen.
 * @param sourceMap                   Eine Map von linearen Adressen zu ihren ursprünglichen Quellcode-Zeilen.
 * @param callSiteBindings            Metadaten für Prozeduraufrufe: Eine Map von der linearen Adresse einer CALL-Instruktion zu den IDs der gebundenen Register.
 * @param relativeCoordToLinearAddress Eine Map zur Umwandlung von relativen Koordinaten (relativ zum Programmstart) in lineare Adressen.
 * @param linearAddressToCoord        Die umgekehrte Map von linearen Adressen zu relativen Koordinaten.
 * @param labelAddressToName          Eine Map von linearen Adressen zu den dort definierten Label-Namen.
 */
public record ProgramArtifact(
        String programId,
        Map<int[], Integer> machineCodeLayout,
        Map<int[], PlacedMolecule> initialWorldObjects,
        Map<Integer, SourceInfo> sourceMap,
        Map<Integer, int[]> callSiteBindings,
        Map<List<Integer>, Integer> relativeCoordToLinearAddress,
        Map<Integer, int[]> linearAddressToCoord,
        Map<Integer, String> labelAddressToName
) {
    /**
     * Konstruktor, der sicherstellt, dass alle Maps unveränderlich sind.
     */
    public ProgramArtifact {
        machineCodeLayout = Collections.unmodifiableMap(machineCodeLayout);
        initialWorldObjects = Collections.unmodifiableMap(initialWorldObjects);
        sourceMap = Collections.unmodifiableMap(sourceMap);
        callSiteBindings = Collections.unmodifiableMap(callSiteBindings);
        relativeCoordToLinearAddress = Collections.unmodifiableMap(relativeCoordToLinearAddress);
        linearAddressToCoord = Collections.unmodifiableMap(linearAddressToCoord);
        labelAddressToName = Collections.unmodifiableMap(labelAddressToName);
    }
}
