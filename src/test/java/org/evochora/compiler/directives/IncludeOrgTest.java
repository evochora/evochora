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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import org.evochora.compiler.api.CompilationException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test überprüft, dass .ORG und .DIR Direktiven in eingebundenen Dateien korrekt als relative
 * Koordinaten zur aktuellen Position und Richtung behandelt werden, nicht als absolute.
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
        // Erstelle beide Assembly-Dateien im tempDir
        Path mainFile = tempDir.resolve("test_include_org.s");
        Path incFile = tempDir.resolve("test_include_org_inc.s");
        
        // Definiere die Include-Datei
        List<String> incSource = List.of(
            ".ORG 0|1     # Sollte relativ zu [0,1] sein, also [0,2]",
            "NOP          # Position [0,2] (sollte sein)",
            ".ORG 0|2     # Sollte relativ zu [0,1] sein, also [0,3]",
            "NOP          # Position [0,3] (sollte sein)"
        );
        Files.write(incFile, incSource);

        // Definiere die Hauptdatei
        List<String> mainSource = List.of(
            ".ORG 0|0",
            "NOP          # Position [0,0]",
            "NOP          # Position [1,0]",
            ".ORG 0|1     # Setze Position auf [0,1]",
            ".INCLUDE \"test_include_org_inc.s\"",
            "NOP          # Position [0,4] (sollte sein, nach dem Include)"
        );
        Files.write(mainFile, mainSource);

        // Lese die Hauptdatei als Strings
        List<String> sourceLines = Files.readAllLines(mainFile);

        // Kompiliere die Hauptdatei
        ProgramArtifact artifact = compiler.compile(sourceLines, tempDir.resolve("test_include_org.s").toString());

        // Prüfe die spezifischen Koordinaten für relative Positionierung
        Map<int[], Integer> machineCodeLayout = artifact.machineCodeLayout();
        
        // Debug: Zeige alle Koordinaten
        System.out.println("Alle Koordinaten in machineCodeLayout:");
        machineCodeLayout.keySet().stream()
            .sorted((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                return Integer.compare(a[0], b[0]);
            })
            .forEach(coords -> System.out.println("[" + coords[0] + ", " + coords[1] + "] -> " + machineCodeLayout.get(coords)));

        // Teste die spezifische relative Positionierung:
        
        // 1. Hauptdatei vor Include: [0,0] und [1,0]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 0});
        assertThat(machineCodeLayout).containsKey(new int[]{1, 0});
        
        // 2. Include-Basis: [0,1] (wo der Include gemacht wurde)
        assertThat(machineCodeLayout).containsKey(new int[]{0, 1});
        
        // 3. Include-Inhalt: [0,2] und [0,3] (relativ zu [0,1])
        assertThat(machineCodeLayout).containsKey(new int[]{0, 2});
        assertThat(machineCodeLayout).containsKey(new int[]{0, 3});
        
        // 4. Nach Include: [0,4] (sollte nach dem Include-Inhalt kommen)
        assertThat(machineCodeLayout).containsKey(new int[]{0, 4});
        
        // Prüfe, dass keine unerwarteten Koordinaten existieren
        // Erwartete Koordinaten: [0,0], [1,0], [0,1], [0,2], [0,3], [0,4]
        assertThat(machineCodeLayout).hasSize(6);
        
        System.out.println("Relative Positionierung funktioniert korrekt!");
    }

    @Test
    @Tag("unit")
    void testIncludeDirRelativeDirection() throws Exception {
        // Erstelle beide Assembly-Dateien im tempDir
        Path mainFile = tempDir.resolve("test_include_dir.s");
        Path incFile = tempDir.resolve("test_include_dir_inc.s");
        
        // Definiere die Include-Datei mit .DIR
        List<String> incSource = List.of(
            ".DIR 0|1     # Sollte relativ zur Include-Richtung [1,0] sein, also [1,1]",
            "NOP          # Position [0,1] + [1,1] = [1,2]",
            "NOP          # Position [1,2] + [1,1] = [2,3]",
            ".DIR 1|0     # Sollte relativ zur Include-Richtung [1,0] sein, also [2,0]",
            "NOP          # Position [2,3] + [2,0] = [4,3]"
        );
        Files.write(incFile, incSource);

        // Definiere die Hauptdatei
        List<String> mainSource = List.of(
            ".ORG 0|0",
            ".DIR 1|0     # Setze Richtung auf [1,0]",
            "NOP          # Position [0,0]",
            ".ORG 0|1     # Setze Position auf [0,1]",
            ".INCLUDE \"test_include_dir_inc.s\"",
            "NOP          # Position nach dem Include (sollte bei [5,3] sein)"
        );
        Files.write(mainFile, mainSource);

        // Kompiliere die Hauptdatei
        ProgramArtifact artifact = compiler.compile(Files.readAllLines(mainFile), tempDir.resolve("test_include_dir.s").toString());

        Map<int[], Integer> machineCodeLayout = artifact.machineCodeLayout();
        
        // Debug: Zeige alle Koordinaten
        System.out.println("Alle Koordinaten in machineCodeLayout (DIR Test):");
        machineCodeLayout.keySet().stream()
            .sorted((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                return Integer.compare(a[0], b[0]);
            })
            .forEach(coords -> System.out.println("[" + coords[0] + ", " + coords[1] + "] -> " + machineCodeLayout.get(coords)));

        // Teste die spezifische relative Richtung:
        
        // 1. Hauptdatei vor Include: [0,0]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 0});
        
        // 2. Include-Basis: [0,1]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 1});
        
        // 3. Include-Inhalt mit .DIR 0|1 (relativ zu [1,0] = [1,1]): [1,2], [2,3]
        assertThat(machineCodeLayout).containsKey(new int[]{1, 2});
        assertThat(machineCodeLayout).containsKey(new int[]{2, 3});
        
        // 4. Include-Inhalt mit .DIR 1|0 (relativ zu [1,0] = [2,0]): [4,3]
        assertThat(machineCodeLayout).containsKey(new int[]{4, 3});
        
        // 5. Nach Include: [5,3] (sollte nach dem Include-Inhalt kommen)
        assertThat(machineCodeLayout).containsKey(new int[]{5, 3});
        
        // Prüfe, dass keine unerwarteten Koordinaten existieren
        assertThat(machineCodeLayout).hasSize(6);
        
        System.out.println("Relative Richtung funktioniert korrekt!");
    }

    @Test
    @Tag("unit")
    void testNestedIncludes() throws Exception {
        // Erstelle drei Assembly-Dateien im tempDir
        Path mainFile = tempDir.resolve("test_nested_includes.s");
        Path inc1File = tempDir.resolve("test_nested_inc1.s");
        Path inc2File = tempDir.resolve("test_nested_inc2.s");
        
        // Definiere die zweite Include-Datei (wird von der ersten eingebunden)
        List<String> inc2Source = List.of(
            ".ORG 0|1     # Sollte relativ zu inc1-Basis [1,1] sein, also [1,2]",
            "NOP          # Position [1,2]",
            ".DIR 0|1     # Sollte relativ zu inc1-Richtung [0,1] sein, also [0,2]",
            "NOP          # Position [1,2] + [0,2] = [1,4]"
        );
        Files.write(inc2File, inc2Source);
        
        // Definiere die erste Include-Datei (wird von der Hauptdatei eingebunden)
        List<String> inc1Source = List.of(
            ".ORG 0|1     # Sollte relativ zu Hauptdatei-Basis [0,0] sein, also [0,1]",
            "NOP          # Position [0,1]",
            ".DIR 0|1     # Sollte relativ zu Hauptdatei-Richtung [1,0] sein, also [1,1]",
            "NOP          # Position [0,1] + [1,1] = [1,2]",
            ".INCLUDE \"test_nested_inc2.s\"",
            "NOP          # Position nach dem Include (sollte bei [1,5] sein)"
        );
        Files.write(inc1File, inc1Source);

        // Definiere die Hauptdatei
        List<String> mainSource = List.of(
            ".ORG 0|0",
            ".DIR 1|0     # Setze Richtung auf [1,0]",
            "NOP          # Position [0,0]",
            ".INCLUDE \"test_nested_inc1.s\"",
            "NOP          # Position nach dem Include (sollte bei [2,0] sein)"
        );
        Files.write(mainFile, mainSource);

        // Kompiliere die Hauptdatei
        ProgramArtifact artifact = compiler.compile(Files.readAllLines(mainFile), tempDir.resolve("test_nested_includes.s").toString());

        Map<int[], Integer> machineCodeLayout = artifact.machineCodeLayout();
        
        // Debug: Zeige alle Koordinaten
        System.out.println("Alle Koordinaten in machineCodeLayout (Nested Includes Test):");
        machineCodeLayout.keySet().stream()
            .sorted((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                return Integer.compare(a[0], b[0]);
            })
            .forEach(coords -> System.out.println("[" + coords[0] + ", " + coords[1] + "] -> " + machineCodeLayout.get(coords)));

        // Teste verschachtelte Includes:
        
        // 1. Hauptdatei vor Include: [0,0]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 0});
        
        // 2. Erste Include-Basis: [0,0] (Hauptdatei-Basis)
        // 3. Erste Include-Inhalt: [0,1], [1,2]
        assertThat(machineCodeLayout).containsKey(new int[]{0, 1});
        assertThat(machineCodeLayout).containsKey(new int[]{1, 2});
        
        // 4. Zweite Include-Basis: [1,2] (erste Include-Position)
        // 5. Zweite Include-Inhalt: [1,3], [1,5]
        assertThat(machineCodeLayout).containsKey(new int[]{1, 3});
        assertThat(machineCodeLayout).containsKey(new int[]{1, 5});
        
        // 6. Nach dem ersten Include: [2,0] (Hauptdatei-Richtung)
        assertThat(machineCodeLayout).containsKey(new int[]{2, 0});
        
        // Prüfe, dass keine unerwarteten Koordinaten existieren
        assertThat(machineCodeLayout).hasSize(6);
        
        System.out.println("Verschachtelte Includes funktionieren korrekt!");
    }

    @Test
    void testDuplicateCoordinatesInMachineCodeLayout() throws Exception {
        // Erstelle eine Assembly-Datei, die doppelte Koordinaten erzeugt
        Path mainFile = tempDir.resolve("test_duplicate_coords.s");
        
        // Definiere eine Assembly-Datei mit doppelten Koordinaten
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

        // Lese die Hauptdatei als Strings
        List<String> sourceLines = Files.readAllLines(mainFile);

        // Test: Erwarte, dass die Kompilierung bei Adresskonflikt fehlschlägt
        CompilationException exception = assertThrows(CompilationException.class, () -> {
            compiler.compile(sourceLines, tempDir.resolve("test_duplicate_coords.s").toString());
        });

        // Prüfe, dass die Fehlermeldung die richtigen Informationen enthält
        String errorMessage = exception.getMessage();
        
        // Prüfe, dass die Koordinate [0, 0] in der Fehlermeldung steht
        assertThat(errorMessage).contains("[0, 0]");
        
        // Prüfe, dass die Fehlermeldung von einer "opcode instruction" spricht
        assertThat(errorMessage).contains("opcode instruction");
        
        // Prüfe, dass die Fehlermeldung beide Zeilen erwähnt (2 und 4)
        assertThat(errorMessage).contains(":2");
        assertThat(errorMessage).contains(":4");
        
        // Prüfe, dass es sich um einen "Address conflict" handelt
        assertThat(errorMessage).contains("Address conflict");
        
        System.out.println("Adresskonflikt korrekt erkannt: " + errorMessage);
    }
}
