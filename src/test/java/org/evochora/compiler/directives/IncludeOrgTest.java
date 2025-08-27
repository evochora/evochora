package org.evochora.compiler.directives;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.evochora.compiler.api.CompilationException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test überprüft, dass .ORG und .DIR Direktiven in eingebundenen Dateien korrekt behandelt werden:
 * - .ORG: relativ zur Include-Position
 * - .DIR: absolut (setzt Richtung auf Einheitsvektor), wird beim Verlassen zurückgesetzt
 */
@Tag("integration")
public class IncludeOrgTest {

    @TempDir
    Path tempDir;

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        // Initialisiere alle Anweisungen vor dem Kompilieren
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    @Tag("unit")
    void testIncludeOrgRelativePositioning() throws Exception {
        // Test der relativen .ORG Positionierung in Include-Dateien
        // Formel: neuePosition = Include-Position + .ORG-Argument
        
        // Erstelle die Include-Datei
        Path incFile = tempDir.resolve("inc1.s");
        List<String> incSource = List.of(
            ".ORG 0|1",           // Relativ zu [0,1] = [0,2]
            "NOP",                // Position [0,2]
            ".ORG 0|2",           // Relativ zu [0,1] = [0,3] 
            "NOP"                 // Position [0,3]
        );
        Files.write(incFile, incSource);
        
        // Hauptdatei: Start bei [0,0], dann .ORG [0,1], dann Include
        Path mainFile = tempDir.resolve("main.s");
        List<String> mainSource = List.of(
            ".ORG 0|0",           // Absolute Position [0,0]
            "NOP",                // Position [0,0]
            "NOP",                // Position [1,0] 
            ".ORG 0|1",           // Absolute Position [0,1] - Include-Basis
            ".INCLUDE \"inc1.s\"", // Include bei Position [0,1]
            "NOP"                 // Nach Include: Position bleibt wo der letzte Befehl endete
        );
        Files.write(mainFile, mainSource);

        // Kompiliere die Hauptdatei
        ProgramArtifact artifact = compiler.compile(Files.readAllLines(mainFile), mainFile.toString());
        
        Map<int[], Integer> machineCodeLayout = artifact.machineCodeLayout();
        
        // Debug: Zeige alle Koordinaten
        System.out.println("Alle Koordinaten in machineCodeLayout:");
        machineCodeLayout.keySet().stream()
            .sorted((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                return Integer.compare(a[0], b[0]);
            })
            .forEach(coords -> System.out.println("[" + coords[0] + ", " + coords[1] + "] -> " + machineCodeLayout.get(coords)));

        // Teste die mathematische Korrektheit der relativen Positionierung:
        
        // 1. Hauptdatei vor Include: [0,0] und [1,0]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 0});
        assertThat(machineCodeLayout).containsKey(new int[]{1, 0});
        
        // 2. Include-Basis: [0,1] (wo der Include gemacht wurde)
        assertThat(machineCodeLayout).containsKey(new int[]{0, 1});
        
        // 3. Include-Inhalt: [0,2] und [0,3] (relativ zu [0,1])
        // .ORG 0|1 relativ zu [0,1] = [0,2]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 2});
        // .ORG 0|2 relativ zu [0,1] = [0,3]  
        assertThat(machineCodeLayout).containsKey(new int[]{0, 3});
        
        // 4. Nach Include: Position bleibt bei [0,3] (wo der letzte Befehl endete)
        // Der NOP nach dem Include sollte bei [1,3] sein (Richtung [1,0])
        assertThat(machineCodeLayout).containsKey(new int[]{1, 3});
        
        // Prüfe, dass keine unerwarteten Koordinaten existieren
        // Erwartete Koordinaten: [0,0], [1,0], [0,1], [0,2], [0,3], [1,3]
        assertThat(machineCodeLayout).hasSize(6);
        
        System.out.println("Relative .ORG Positionierung funktioniert korrekt!");
    }

    @Test
    @Tag("unit")
    void testIncludeDirRelativeDirection() throws Exception {
        // Test der .DIR Richtung in Include-Dateien
        // .DIR setzt die Richtung absolut auf den angegebenen Einheitsvektor
        // Beim Verlassen der Include-Datei wird die ursprüngliche Richtung wiederhergestellt
        
        // Erstelle die Include-Datei
        Path incFile = tempDir.resolve("inc2.s");
        List<String> incSource = List.of(
            ".DIR 0|1",           // Richtung absolut auf [0,1] (unten)
            "NOP",                // Position [0,1] + [0,1] = [0,2]
            "NOP",                // Position [0,2] + [0,1] = [0,3]
            ".DIR 1|0",           // Richtung absolut auf [1,0] (rechts)
            "NOP"                 // Position [0,3] + [1,0] = [1,3]
        );
        Files.write(incFile, incSource);
        
        // Hauptdatei: Start bei [0,0], Richtung [1,0], dann .ORG [0,1], dann Include
        Path mainFile = tempDir.resolve("main.s");
        List<String> mainSource = List.of(
            ".ORG 0|0",           // Absolute Position [0,0]
            ".DIR 1|0",           // Absolute Richtung [1,0]
            "NOP",                // Position [0,0]
            ".ORG 0|1",           // Absolute Position [0,1] - Include-Basis
            ".INCLUDE \"inc2.s\"", // Include bei Position [0,1], Richtung [1,0]
            "NOP"                 // Nach Include: Position bleibt, Richtung wird zurückgesetzt auf [1,0]
        );
        Files.write(mainFile, mainSource);

        // Kompiliere die Hauptdatei
        ProgramArtifact artifact = compiler.compile(Files.readAllLines(mainFile), mainFile.toString());
        
        Map<int[], Integer> machineCodeLayout = artifact.machineCodeLayout();
        
        // Debug: Zeige alle Koordinaten
        System.out.println("Alle Koordinaten in machineCodeLayout (DIR Test):");
        machineCodeLayout.keySet().stream()
            .sorted((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                return Integer.compare(a[0], b[0]);
            })
            .forEach(coords -> System.out.println("[" + coords[0] + ", " + coords[1] + "] -> " + machineCodeLayout.get(coords)));

        // Teste die absolute .DIR Richtung und Kontext-Restoration:
        
        // 1. Hauptdatei vor Include: [0,0]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 0});
        
        // 2. Include-Basis: [0,1]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 1});
        
        // 3. Include-Inhalt mit .DIR 0|1 (Richtung absolut auf [0,1]): [0,2], [0,3]
        // .DIR 0|1 setzt Richtung absolut auf [0,1] (unten)
        // NOP bei [0,1] + [0,1] = [0,2]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 2});
        // NOP bei [0,2] + [0,1] = [0,3]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 3});
        
        // 4. Include-Inhalt mit .DIR 1|0 (Richtung absolut auf [1,0]): [1,3]
        // .DIR 1|0 setzt Richtung absolut auf [1,0] (rechts)
        // NOP bei [0,3] + [1,0] = [1,3]
        assertThat(machineCodeLayout).containsKey(new int[]{1, 3});
        
        // 5. Nach Include: Position bleibt bei [1,3], Richtung wird zurückgesetzt auf [1,0]
        // Der NOP nach dem Include sollte bei [2,3] sein (Richtung [1,0])
        assertThat(machineCodeLayout).containsKey(new int[]{2, 3});
        
        // Prüfe, dass keine unerwarteten Koordinaten existieren
        assertThat(machineCodeLayout).hasSize(6);
        
        System.out.println("Absolute .DIR Richtung und Kontext-Restoration funktionieren korrekt!");
    }

    @Test
    @Tag("unit")
    void testNestedIncludes() throws Exception {
        // Test verschachtelter Includes mit Stack-basierter Kontext-Verwaltung
        // Push beim Include, Pop beim Verlassen
        
        // Erstelle die zweite Include-Datei (wird von der ersten eingebunden)
        Path inc2File = tempDir.resolve("inc2.s");
        List<String> inc2Source = List.of(
            ".ORG 0|1",           // Relativ zu [0,0] = [0,1]
            "NOP",                // Position [0,2] + [0,1] = [0,3]
            ".DIR 1|0",           // Richtung absolut auf [1,0] (rechts)
            "NOP"                 // Position [0,3] + [1,0] = [1,3]
        );
        Files.write(inc2File, inc2Source);
        
        // Erstelle die erste Include-Datei (wird von der Hauptdatei eingebunden)
        Path inc1File = tempDir.resolve("inc1.s");
        List<String> inc1Source = List.of(
            ".ORG 0|1",           // Relativ zu [0,0] = [0,1]
            "NOP",                // Position [0,1]
            ".DIR 0|1",           // Richtung absolut auf [0,1] (unten)
            "NOP",                // Position [0,1] + [0,1] = [0,2]
            ".INCLUDE \"inc2.s\"", // Include bei Position [0,2], Richtung [0,1]
            "NOP"                 // Nach Include: Position bleibt, Richtung wird zurückgesetzt auf [0,1]
        );
        Files.write(inc1File, inc1Source);
        
        // Hauptdatei: Start bei [0,0], Richtung [1,0]
        Path mainFile = tempDir.resolve("main.s");
        List<String> mainSource = List.of(
            ".ORG 0|0",           // Absolute Position [0,0]
            ".DIR 1|0",           // Absolute Richtung [1,0]
            "NOP",                // Position [0,0]
            ".INCLUDE \"inc1.s\"", // Include bei Position [0,0], Richtung [1,0]
            "NOP"                 // Nach Include: Position bleibt, Richtung wird zurückgesetzt auf [1,0]
        );
        Files.write(mainFile, mainSource);

        // Kompiliere die Hauptdatei
        ProgramArtifact artifact = compiler.compile(Files.readAllLines(mainFile), mainFile.toString());
        
        Map<int[], Integer> machineCodeLayout = artifact.machineCodeLayout();
        
        // Debug: Zeige alle Koordinaten
        System.out.println("Alle Koordinaten in machineCodeLayout (Nested Includes Test):");
        machineCodeLayout.keySet().stream()
            .sorted((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                return Integer.compare(a[0], b[0]);
            })
            .forEach(coords -> System.out.println("[" + coords[0] + ", " + coords[1] + "] -> " + machineCodeLayout.get(coords)));

        // Teste verschachtelte Includes mit Stack-Verhalten:
        
        // 1. Hauptdatei vor Include: [0,0]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 0});
        
        // 2. Erste Include-Basis: [0,0] (Hauptdatei-Basis)
        // 3. Erste Include-Inhalt: [0,1], [0,2]
        // .ORG 0|1 relativ zu [0,0] = [0,1]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 1});
        // .DIR 0|1 setzt Richtung absolut auf [0,1], dann NOP bei [0,1] + [0,1] = [0,2]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 2});
        
        // 4. Zweite Include-Basis: [0,2] (erste Include-Position)
        // 5. Zweite Include-Inhalt: [0,3], [1,3]
        // .ORG 0|1 relativ zu [0,0] = [0,1], dann NOP bei [0,2] + [0,1] = [0,3]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 3});
        // .DIR 1|0 setzt Richtung absolut auf [1,0], dann NOP bei [0,3] + [1,0] = [1,3]
        assertThat(machineCodeLayout).containsKey(new int[]{1, 3});
        
        // 6. Nach dem ersten Include: Position bleibt bei [1,3], Richtung wird zurückgesetzt auf [1,0]
        // Der NOP nach dem Include sollte bei [2,3] sein (Richtung [1,0])
        assertThat(machineCodeLayout).containsKey(new int[]{2, 3});
        
        // Prüfe, dass keine unerwarteten Koordinaten existieren
        assertThat(machineCodeLayout).hasSize(6);
        
        System.out.println("Verschachtelte Includes mit Stack-Verhalten funktionieren korrekt!");
    }

    @Test
    void testContextRestorationAfterInclude() throws Exception {
        // Test der Kontext-Restoration nach dem Verlassen einer Include-Datei
        // Position bleibt, Richtung und Basis werden zurückgesetzt
        
        // Erstelle die Include-Datei
        Path incFile = tempDir.resolve("inc3.s");
        List<String> incSource = List.of(
            ".DIR 0|1",           // Richtung absolut auf [0,1] (unten)
            "NOP",                // Position [0,1] + [0,1] = [0,2]
            ".ORG 0|1",           // Relativ zu [0,1] = [0,2]
            "NOP"                 // Position [0,2]
        );
        Files.write(incFile, incSource);
        
        // Hauptdatei: Start bei [0,0], Richtung [1,0]
        Path mainFile = tempDir.resolve("main.s");
        List<String> mainSource = List.of(
            ".ORG 0|0",           // Absolute Position [0,0]
            ".DIR 1|0",           // Absolute Richtung [1,0]
            "NOP",                // Position [0,0]
            ".ORG 0|1",           // Absolute Position [0,1] - Include-Basis
            ".INCLUDE \"inc3.s\"", // Include bei Position [0,1], Richtung [1,0]
            ".DIR 0|1",           // Nach Include: Richtung sollte [0,1] sein (absolut)
            "NOP"                 // Position sollte bei [0,1] + [0,1] = [0,2] sein
        );
        Files.write(mainFile, mainSource);

        // Kompiliere die Hauptdatei
        ProgramArtifact artifact = compiler.compile(Files.readAllLines(mainFile), mainFile.toString());
        
        Map<int[], Integer> machineCodeLayout = artifact.machineCodeLayout();
        
        // Debug: Zeige alle Koordinaten
        System.out.println("Alle Koordinaten in machineCodeLayout (Context Restoration Test):");
        machineCodeLayout.keySet().stream()
            .sorted((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                return Integer.compare(a[0], b[0]);
            })
            .forEach(coords -> System.out.println("[" + coords[0] + ", " + coords[1] + "] -> " + machineCodeLayout.get(coords)));

        // Teste Kontext-Restoration:
        
        // 1. Hauptdatei vor Include: [0,0]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 0});
        
        // 2. Include-Basis: [0,1]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 1});
        
        // 3. Include-Inhalt: [0,2], [0,2]
        // .DIR 0|1 setzt Richtung absolut auf [0,1], dann NOP bei [0,1] + [0,1] = [0,2]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 2});
        // .ORG 0|1 relativ zu [0,1] = [0,2], dann NOP bei [0,2]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 2});
        
        // 4. Nach Include: Position bleibt bei [0,2], Richtung wird zurückgesetzt auf [1,0]
        // .DIR 0|1 setzt Richtung absolut auf [0,1], dann NOP bei [0,2] + [0,1] = [0,3]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 3});
        
        // Prüfe, dass keine unerwarteten Koordinaten existieren
        assertThat(machineCodeLayout).hasSize(4);
        
        System.out.println("Kontext-Restoration nach Include funktioniert korrekt!");
    }

    @Test
    void testDuplicateCoordinatesInMachineCodeLayout() throws Exception {
        // Test: Erwarte, dass die Kompilierung bei Adresskonflikt fehlschlägt
        
        // Definiere eine Assembly-Datei mit doppelten Koordinaten
        Path mainFile = tempDir.resolve("test_duplicate_coords.s");
        List<String> mainSource = List.of(
            ".ORG 0|0",
            "NOP",
            ".ORG 0|0",  // Setze Position wieder auf [0,0] - verursacht Adresskonflikt
            "NOP",
            ".ORG 1|0",
            "NOP",
            ".ORG 0|1",
            "NOP"
        );
        Files.write(mainFile, mainSource);

        // Test: Erwarte, dass die Kompilierung bei Adresskonflikt fehlschlägt
        CompilationException exception = assertThrows(CompilationException.class, () -> {
            compiler.compile(Files.readAllLines(mainFile), mainFile.toString());
        });

        // Prüfe, dass die Fehlermeldung die richtigen Informationen enthält
        String errorMessage = exception.getMessage();
        
        // Prüfe, dass die Koordinate [0, 0] in der Fehlermeldung steht
        assertThat(errorMessage).contains("[0, 0]");
        
        // Prüfe, dass die Fehlermeldung von einer "opcode instruction" spricht
        assertThat(errorMessage).contains("opcode instruction");
        
        // Prüfe, dass es sich um einen "Address conflict" handelt
        assertThat(errorMessage).contains("Address conflict");
        
        System.out.println("Adresskonflikt korrekt erkannt: " + errorMessage);
    }
}
