package org.evochora.compiler.backend;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.Expansion;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceFile.Note;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The artifact shows the result of every computation as a note {@code [=value]} behind its last
 * token, in the source entry or the macro instance the token stands in, whichever phase computed
 * it: the semantic analysis, the layout or the preprocessor at read time.
 */
@Tag("integration")
class NotesIntegrationTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * A computation in a macro body is noted in the instance of each expansion; one written as an
     * argument stands on the call line and is noted in the main file's entry.
     */
    @Test
    void aComputationInAMacroBodyIsNotedInItsInstance_andAnArgumentAtTheCallLine() throws Exception {
        write("main.evo",
                ".CONST WIDTH 99",
                ".MACRO STEP N",
                "  SETI %DR0 DATA:(WIDTH+1)",
                "  SETI %DR1 N",
                ".ENDMACRO",
                "START:",
                "  STEP DATA:(WIDTH-1)");

        ProgramArtifact artifact = compile();

        SourceFile main = artifact.sources().get(0);
        assertThat(main.notes()).containsExactly(new Note(0, 7, 22, "[=DATA:98]"));
        assertThat(artifact.expansions()).hasSize(1);
        Expansion.Binding binding = artifact.expansions().values().iterator().next().bindings().get(0);
        assertThat(binding.argument()).isEqualTo("DATA:(WIDTH-1)");
        int instance = artifact.expansions().keySet().iterator().next();
        assertThat(artifact.expansions().get(instance).notes())
                .containsExactly(new Note(instance, 3, 27, "[=DATA:100]"));
    }

    /**
     * A value a module requires, computed at the import line, is noted there with the position
     * it stands for.
     */
    @Test
    void aValueHandedToAModuleIsNotedAtTheImportLine() throws Exception {
        write("shell.evo",
                ".REQUIRE VALUE BODY_SE",
                ".ORG BODY_SE",
                "CSE:",
                ".ORG 0|0",
                "NOP");
        write("main.evo",
                ".CONST WIDTH 100",
                ".CONST HEIGHT 70",
                ".IMPORT \"shell.evo\" AS S USING VALUE BODY_SE POSITION(WIDTH-1|HEIGHT-1)",
                "NOP");

        ProgramArtifact artifact = compile();

        assertThat(artifact.sources().get(0).notes()).containsExactly(new Note(0, 3, 72, "[=99|69]"));
    }

    /**
     * A default of a required value that the layout computes is noted behind the default's last
     * token, in the module's entry.
     */
    @Test
    void aDefaultTheLayoutComputesIsNotedBehindTheDefault() throws Exception {
        write("lib.evo",
                ".REQUIRE VALUE X (@ + (0|2))",
                ".ORG X",
                "NOP");
        write("main.evo",
                ".ORG 5|5",
                ".IMPORT \"lib.evo\" AS LIB");

        ProgramArtifact artifact = compile();

        SourceFile module = artifact.sources().stream()
                .filter(source -> "LIB".equals(source.placement())).findFirst().orElseThrow();
        assertThat(module.notes()).containsExactly(new Note(0, 1, 29, "[=5|7]"));
    }

    /**
     * A computation with the cursor is computed by the layout, which notes the position.
     */
    @Test
    void aComputationTheLayoutComputesIsNotedWithItsValue() throws Exception {
        write("main.evo",
                "NOP",
                ".ORG (@ + (0|2))",
                "NOP");

        ProgramArtifact artifact = compile();

        assertThat(artifact.sources().get(0).notes()).containsExactly(new Note(0, 2, 17, "[=1|2]"));
    }

    /**
     * A computation the preprocessor computes at read time is noted behind its last token; a
     * number or a flag name written alone gets no note of its own.
     */
    @Test
    void aComputationAtReadTimeIsNotedBehindItsLastToken() throws Exception {
        write("main.evo",
                ".DEFINE N 2",
                ".REPEAT (N*2)",
                "NOP",
                ".ENDREPEAT",
                ".DEFINE A 2",
                ".IFDEF (A+1) = 3",
                "NOP",
                ".ENDDEF");

        ProgramArtifact artifact = compile();

        assertThat(artifact.sources().get(0).notes())
                .containsExactly(new Note(0, 2, 14, "[=4]"), new Note(0, 6, 13, "[=3]"));
    }

    /**
     * The copies of a repeated body carry no instance of their own and share their positions:
     * a computation with the same value in every copy is noted once, one whose value differs per
     * copy is noted with every value.
     */
    @Test
    void aComputationInARepeatedBodyIsNotedOncePerDistinctValue() throws Exception {
        write("main.evo",
                ".CONST WIDTH 99",
                "NOP",
                ".REPEAT 2",
                "SETI %DR0 DATA:(WIDTH+1)",
                ".ORG (@ + (0|2))",
                "NOP",
                ".ENDREPEAT");

        ProgramArtifact artifact = compile();

        assertThat(artifact.sources().get(0).notes()).containsExactlyInAnyOrder(
                new Note(0, 4, 25, "[=DATA:100]"),
                new Note(0, 5, 17, "[=4|2]"),
                new Note(0, 5, 17, "[=8|4]"));
    }

    /**
     * A configured value stands in no file: its computation compiles without a note, whether the
     * analysis or the layout computes it.
     */
    @Test
    void aConfiguredComputationIsNotNoted() throws Exception {
        write("main.evo",
                ".CONST WIDTH DATA:100",
                ".REQUIRE VALUE X",
                ".REQUIRE VALUE Y",
                "SETI %DR0 X",
                ".ORG Y",
                "NOP");

        ProgramArtifact artifact = compile(Map.of("X", "(WIDTH-1)", "Y", "(@ + (0|1))"));

        assertThat(artifact.sources()).allSatisfy(source -> assertThat(source.notes()).isEmpty());
        assertThat(artifact.expansions()).isEmpty();
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile() throws Exception {
        return compile(Map.of());
    }

    private ProgramArtifact compile(Map<String, String> values) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), Map.of(), values);
        return new Compiler().compile("main.evo", ENV, options);
    }
}
