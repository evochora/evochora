package org.evochora.compiler.module;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.api.TokenInfo;
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
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The artifact names the placement of every position: two placements of one file share its text
 * but not its code or the meaning of its tokens, so the sources hold the file once per placement,
 * and the source map, the token map, the token lookup and the instructions per line keep the
 * placements apart. Programs are written to disk and compiled through the whole pipeline.
 */
@Tag("integration")
class PlacementArtifactIntegrationTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aFileImportedTwiceHasOneSourceEntryPerPlacement_afterTheMainFile() throws Exception {
        writeTwiceImportedProgram();

        ProgramArtifact artifact = compile("main.evo", null);

        String lib = resolved("lib/work.evo");
        assertThat(artifact.sources())
                .extracting(SourceFile::placement, SourceFile::path, SourceFile::resolvedPath)
                .containsExactly(
                        tuple("", "main.evo", resolved("main.evo")),
                        tuple("FIRST", "lib/work.evo", lib),
                        tuple("SECOND", "lib/work.evo", lib));
        assertThat(artifact.sources().get(1).lines()).isEqualTo(artifact.sources().get(2).lines());
        assertThat(artifact.sources().get(1).lines()).first().isEqualTo("EXPORT .PROC WORK");
    }

    @Test
    void eachPlacementHasItsOwnTokens_namingTheLabelsOfThatPlacement() throws Exception {
        writeTwiceImportedProgram();

        ProgramArtifact artifact = compile("main.evo", null);

        String lib = resolved("lib/work.evo");
        // "  JMPI LOOP" on line 4: the label reference begins in column 8
        TokenInfo first = artifact.tokenLookup().get("FIRST").get(lib).get(4).get(8).getFirst();
        TokenInfo second = artifact.tokenLookup().get("SECOND").get(lib).get(4).get(8).getFirst();
        assertThat(first.qualifiedName()).isEqualTo("FIRST.LOOP");
        assertThat(second.qualifiedName()).isEqualTo("SECOND.LOOP");
        assertThat(artifact.labelNameToValue().get("FIRST.LOOP"))
                .isNotEqualTo(artifact.labelNameToValue().get("SECOND.LOOP"));
        assertThat(artifact.tokenMap().get(new SourceInfo(lib, 4, 8, "SECOND"))).isEqualTo(second);
    }

    @Test
    void theSourceMapAndTheInstructionsPerLineNameThePlacementOfAnInstruction() throws Exception {
        writeTwiceImportedProgram();

        ProgramArtifact artifact = compile("main.evo", null);

        String lib = resolved("lib/work.evo");
        // "  NOP" on line 3, once in each placement
        assertThat(artifact.sourceMap().values())
                .filteredOn(info -> info.fileName().equals(lib) && info.lineNumber() == 3)
                .extracting(SourceInfo::placement)
                .containsExactlyInAnyOrder("FIRST", "SECOND");
        assertThat(artifact.sourceLineToInstructions())
                .containsKeys("FIRST@" + lib + ":3", "SECOND@" + lib + ":3")
                .doesNotContainKey(lib + ":3");
        assertThat(artifact.sourceLineToInstructions().get("SECOND@" + lib + ":3")).hasSize(1);
        assertThat(artifact.sourceLineToInstructions()).containsKey(resolved("main.evo") + ":5");
    }

    @Test
    void aSourcedFileStandsUnderThePlacementThatIncludesIt_atItsDirective() throws Exception {
        write("lib/inc.evo",
                "  NOP");
        write("lib/mod.evo",
                ".SOURCE \"lib/inc.evo\"",
                "EXPORT .PROC WORK",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".SOURCE \"lib/inc.evo\"",
                ".IMPORT \"lib/mod.evo\" AS MOD",
                ".SOURCE \"lib/inc.evo\"",
                "START:",
                "  CALL MOD.WORK");

        ProgramArtifact artifact = compile("main.evo", null);

        String inc = resolved("lib/inc.evo");
        assertThat(artifact.sources())
                .extracting(SourceFile::placement, SourceFile::path, SourceFile::resolvedPath)
                .containsExactly(
                        tuple("", "main.evo", resolved("main.evo")),
                        tuple("", "lib/inc.evo", inc),
                        tuple("MOD", "lib/mod.evo", resolved("lib/mod.evo")),
                        tuple("MOD", "lib/inc.evo", inc));
        assertThat(artifact.sourceMap().values())
                .filteredOn(info -> info.fileName().equals(inc))
                .extracting(SourceInfo::placement)
                .containsExactlyInAnyOrder("", "", "MOD");
    }

    @Test
    void aMacroFromASourcedFileExpandsInThePlacementThatSourcesIt() throws Exception {
        write("lib/macros.evo",
                ".MACRO PAD",
                "  NOP",
                ".ENDMACRO");
        write("lib/mod.evo",
                ".SOURCE \"lib/macros.evo\"",
                "EXPORT .PROC WORK",
                "  PAD",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".IMPORT \"lib/mod.evo\" AS FIRST",
                ".IMPORT \"lib/mod.evo\" AS SECOND",
                "START:",
                "  CALL FIRST.WORK",
                "  CALL SECOND.WORK");

        ProgramArtifact artifact = compile("main.evo", null);

        String macros = resolved("lib/macros.evo");
        assertThat(artifact.sourceMap().values())
                .filteredOn(info -> info.fileName().equals(macros))
                .extracting(SourceInfo::lineNumber, SourceInfo::placement)
                .containsExactlyInAnyOrder(tuple(2, "FIRST"), tuple(2, "SECOND"));
    }

    @Test
    void theMainFileStandsInTheChainOfItsPrefix_andTheImportsBelowIt() throws Exception {
        writeTwiceImportedProgram();

        ProgramArtifact artifact = compile("APP:main.evo", "APP");

        assertThat(artifact.sources())
                .extracting(SourceFile::placement, SourceFile::path)
                .containsExactly(
                        tuple("APP", "APP:main.evo"),
                        tuple("APP.FIRST", "lib/work.evo"),
                        tuple("APP.SECOND", "lib/work.evo"));
        assertThat(artifact.sourceMap().values())
                .filteredOn(info -> info.fileName().equals(resolved("main.evo")))
                .extracting(SourceInfo::placement)
                .containsOnly("APP");
    }

    /**
     * Writes {@code lib/work.evo}, a procedure with a label of its own, and a main file that
     * imports it twice and calls both placements.
     */
    private void writeTwiceImportedProgram() throws Exception {
        write("lib/work.evo",
                "EXPORT .PROC WORK",
                "LOOP:",
                "  NOP",
                "  JMPI LOOP",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".IMPORT \"lib/work.evo\" AS FIRST",
                ".IMPORT \"lib/work.evo\" AS SECOND",
                "START:",
                "  CALL FIRST.WORK",
                "  CALL SECOND.WORK");
    }

    private String resolved(String fileName) {
        return sourceRoot.resolve(fileName).toString().replace('\\', '/');
    }

    private void write(String fileName, String... lines) throws Exception {
        Path file = sourceRoot.resolve(fileName);
        Files.createDirectories(Objects.requireNonNull(file.getParent()));
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    /**
     * Compiles a program from the source root, which the imports reach without a prefix; with a
     * prefix given, the same directory is also a root under that prefix, for the program name.
     */
    private ProgramArtifact compile(String programName, String rootPrefix) throws Exception {
        List<SourceRoot> roots = rootPrefix == null
                ? List.of(new SourceRoot(sourceRoot.toString(), null))
                : List.of(new SourceRoot(sourceRoot.toString(), null),
                        new SourceRoot(sourceRoot.toString(), rootPrefix));
        CompilerOptions options = new CompilerOptions(roots, Map.of());
        return new Compiler().compile(programName, ENV, options);
    }
}
