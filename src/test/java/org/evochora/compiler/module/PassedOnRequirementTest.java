package org.evochora.compiler.module;

import java.nio.file.Files;
import java.nio.file.Path;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that a module can hand on a dependency it received itself.
 * <p>
 * A module declares with {@code .REQUIRE} that it needs something without saying which module that
 * is; whoever imports it decides, through a {@code USING} clause. A module in the middle of such a
 * chain has the same freedom to leave the choice open: what it received under one name it hands to
 * its own import under another, so only the outermost caller decides what actually arrives at the
 * bottom.
 */
class PassedOnRequirementTest {

    @TempDir
    Path tempDir;

    private Compiler compiler;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        compiler = new Compiler();
    }

    @Test
    @Tag("integration")
    void aRequirementReceivedFromAboveCanBeHandedDownFurther() throws Exception {
        // The module at the bottom needs a math module and does not care which one.
        Files.writeString(tempDir.resolve("math.evo"), String.join("\n",
                "EXPORT .PROC DOUBLE REF X",
                "  ADDI X DATA:0",
                "  RET",
                ".ENDP",
                ""));

        Files.writeString(tempDir.resolve("bottom.evo"), String.join("\n",
                ".REQUIRE \"math.evo\" AS MATH",
                "EXPORT .PROC WORK REF X",
                "  CALL MATH.DOUBLE REF X",
                "  RET",
                ".ENDP",
                ""));

        // The middle module leaves the choice open as well: it requires math and hands on what it
        // was given, without importing a math module of its own.
        Files.writeString(tempDir.resolve("middle.evo"), String.join("\n",
                ".REQUIRE \"math.evo\" AS MATH",
                ".IMPORT \"bottom.evo\" AS BOTTOM USING MATH AS MATH",
                "EXPORT .PROC RUN REF X",
                "  CALL BOTTOM.WORK REF X",
                "  RET",
                ".ENDP",
                ""));

        // Only the outermost module decides which math module reaches the bottom.
        Files.writeString(tempDir.resolve("main.evo"), String.join("\n",
                ".IMPORT \"math.evo\" AS M",
                ".IMPORT \"middle.evo\" AS MIDDLE USING M AS MATH",
                "START:",
                "  SETI %DR0 DATA:1",
                "  CALL MIDDLE.RUN REF %DR0",
                ""));

        assertThatCode(this::compileMain)
                .as("MIDDLE hands the math module it received on to BOTTOM")
                .doesNotThrowAnyException();
    }

    @Test
    @Tag("integration")
    void aUsingSourceThatIsNeitherImportedNorRequiredIsRejected() throws Exception {
        Files.writeString(tempDir.resolve("bottom.evo"), String.join("\n",
                ".REQUIRE \"math.evo\" AS MATH",
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("main.evo"), String.join("\n",
                ".IMPORT \"bottom.evo\" AS BOTTOM USING NOWHERE AS MATH",
                "  NOP",
                ""));

        assertThatCode(this::compileMain)
                .as("a name that is neither imported nor required cannot satisfy anything")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("NOWHERE");
    }

    @Test
    @Tag("integration")
    void modulesThatImportEachOtherAreRejected() throws Exception {
        // Handing a dependency on walks from the outermost module inwards, which terminates
        // because the module graph is acyclic. That is not an assumption but a checked property:
        // a cycle is caught while scanning, before any binding is resolved.
        Files.writeString(tempDir.resolve("a.evo"), String.join("\n",
                ".IMPORT \"b.evo\" AS B",
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("b.evo"), String.join("\n",
                ".IMPORT \"a.evo\" AS A",
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("main.evo"), String.join("\n",
                ".IMPORT \"a.evo\" AS START",
                "  NOP",
                ""));

        assertThatCode(this::compileMain)
                .as("two modules importing each other cannot be ordered")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Circular");
    }

    private ProgramArtifact compileMain() throws Exception {
        Path mainFile = tempDir.resolve("main.evo");
        return compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                new EnvironmentProperties(new int[]{100, 100}, true));
    }
}
