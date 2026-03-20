package org.evochora.compiler.features.macro;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test verifying that conflicting macro definitions (same name, different body)
 * sourced from different files produce a compile-time error.
 */
class MacroConflictTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructionSet() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    @Tag("integration")
    void conflictingMacroDefinitions_throwsError() throws Exception {
        Files.writeString(tempDir.resolve("macros_v1.evo"),
                ".MACRO FOO\n" +
                "  NOP\n" +
                ".ENDM\n");

        Files.writeString(tempDir.resolve("macros_v2.evo"),
                ".MACRO FOO\n" +
                "  SETI %DR0 DATA:42\n" +
                ".ENDM\n");

        Files.writeString(tempDir.resolve("main.evo"),
                ".SOURCE \"macros_v1.evo\"\n" +
                ".SOURCE \"macros_v2.evo\"\n" +
                "NOP\n");

        Compiler compiler = new Compiler();
        CompilerOptions options = new CompilerOptions(
                List.of(new SourceRoot(tempDir.toAbsolutePath().toString(), null)));

        assertThatThrownBy(() -> compiler.compile("main.evo", null, options))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("conflict");
    }
}
