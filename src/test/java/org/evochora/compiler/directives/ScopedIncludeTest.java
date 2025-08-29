package org.evochora.compiler.directives;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class ScopedIncludeTest {

    @TempDir
    Path tempDir;

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    @Tag("integration")
    void testNestedIncludesWithRelativeOrgAndDirContext() throws Exception {
        // === Create test files ===

        // inc2.s (innermost include)
        // Is included from inc1.s at position [3,2] with DV=[0,1] and basePos=[3,2]
        Path inc2File = tempDir.resolve("inc2.s");
        Files.write(inc2File, List.of(
            ".ORG 0|1",      // Relative to base [3,2] -> absolute [3,3]
            "NOP"               // Placed at [3,3]. DV is [0,1] -> next pos is [3,4]
        ));

        // inc1.s (middle include)
        // Is included from main.s at position [1,1] with DV=[1,0] and basePos=[1,1]
        Path inc1File = tempDir.resolve("inc1.s");
        Files.write(inc1File, List.of(
            ".ORG 1|1",      // Relative to base [1,1] -> absolute [2,2]
            "NOP",              // Placed at [2,2]. DV is [1,0] -> next pos is [3,2]
            ".DIR 0|1",      // DV is now [0,1] for the scope of this file (and its includes)
            ".INCLUDE \"inc2.s\"",// Includes at pos [3,2]. After, pos=[3,4]. DV restored to [0,1].
            "NOP"               // Placed at [3,4]. DV is [0,1] -> next pos is [3,5]
        ));

        // main.s (top-level file)
        Path mainFile = tempDir.resolve("main.s");
        Files.write(mainFile, List.of(
            "NOP",              // Placed at [0,0]. Default DV is [1,0] -> next pos is [1,0].
            ".ORG 0|1",      // Absolute position, since base is [0,0].
            "NOP",              // Placed at [0,1]. DV is [1,0] -> next pos is [1,1].
            ".INCLUDE \"inc1.s\"",// Includes at pos [1,1]. After, pos=[3,5]. DV restored to [1,0].
            "NOP"               // Placed at [3,5]. DV is [1,0] -> next pos is [4,5].
        ));

        // === Compile ===
        ProgramArtifact artifact = null;
        try {
            artifact = compiler.compile(mainFile.toAbsolutePath().toString());
        } catch (Exception e) {
            System.err.println("Compilation failed: " + e.getMessage());
            throw e;
        }

        // Convert the map keys from int[] to String for easier assertion
        Map<String, Integer> layout = artifact.machineCodeLayout().entrySet().stream()
            .collect(Collectors.toMap(
                e -> Arrays.toString(e.getKey()),
                Map.Entry::getValue
            ));

        // === Assertions ===
        // Tracing the execution:
        // main:
        // NOP at [0,0]. next=[1,0]
        // ORG to [0,1].
        // NOP at [0,1]. next=[1,1]
        // INCLUDE at [1,1]. base becomes [1,1]. DV is [1,0].
        //   inc1:
        //   ORG [1,1] relative to [1,1] -> [2,2].
        //   NOP at [2,2]. next=[3,2].
        //   DIR [0,1]. DV becomes [0,1].
        //   INCLUDE at [3,2]. base becomes [3,2]. DV is [0,1].
        //     inc2:
        //     ORG [0,1] relative to [3,2] -> [3,3].
        //     NOP at [3,3]. next=[3,4].
        //   END inc2. pos=[3,4]. DV restored to [0,1]. base restored to [1,1].
        //   NOP at [3,4]. next=[3,5].
        // END inc1. pos=[3,5]. DV restored to [1,0]. base restored to [0,0].
        // NOP at [3,5]. next=[4,5].

        assertThat(layout.keySet()).containsExactlyInAnyOrder(
            "[0, 0]",
            "[0, 1]",
            "[2, 2]",
            "[3, 3]",
            "[3, 4]",
            "[3, 5]"
        );
    }
}
