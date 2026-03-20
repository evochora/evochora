package org.evochora.compiler.directives;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
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

/**
 * Tests the compiler's handling of sourced files, particularly how layout directives
 * like `.ORG` and `.DIR` behave across nested {@code .SOURCE} directives.
 * This is an integration test as it involves the filesystem and the full compiler pipeline.
 */
public class ScopedIncludeTest {

    @TempDir
    Path tempDir;

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    /**
     * Verifies that the compiler correctly manages layout contexts (origin and direction)
     * across nested `.SOURCE` directives. The test creates a chain of included files,
     * each modifying the layout context with relative `.ORG` and `.DIR` commands. It then
     * compiles the top-level file and asserts that the final coordinates of all instructions
     * are correct, proving that the layout context is properly saved and restored at each
     * include boundary.
     * <p>
     * This test uses the filesystem to create temporary source files and runs the full
     * compiler, making it an integration test.
     *
     * @throws Exception if file operations or compilation fail.
     */
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
            ".SOURCE \"inc2.s\"",// Includes at pos [3,2]. After, pos=[3,4]. DV restored to [0,1].
            "NOP"               // Placed at [3,4]. DV is [0,1] -> next pos is [3,5]
        ));

        // main.s (top-level file)
        Path mainFile = tempDir.resolve("main.s");
        Files.write(mainFile, List.of(
            "NOP",              // Placed at [0,0]. Default DV is [1,0] -> next pos is [1,0].
            ".ORG 0|1",      // Absolute position, since base is [0,0].
            "NOP",              // Placed at [0,1]. DV is [1,0] -> next pos is [1,1].
            ".SOURCE \"inc1.s\"",// Includes at pos [1,1]. After, pos=[3,5]. DV restored to [1,0].
            "NOP"               // Placed at [3,5]. DV is [1,0] -> next pos is [4,5].
        ));

        // === Compile ===
        ProgramArtifact artifact = null;
        try {
            EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
            artifact = compiler.compile(Files.readAllLines(mainFile), mainFile.toAbsolutePath().toString(), envProps);
        } catch (Exception e) {
            // Compilation failed: " + e.getMessage()
            throw e;
        }

        // Convert the map keys from int[] to String for easier assertion
        Map<String, Integer> layout = artifact.machineCodeLayout().entrySet().stream()
            .collect(Collectors.toMap(
                e -> Arrays.toString(e.getKey()),
                Map.Entry::getValue
            ));

        // === Assertions ===
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
