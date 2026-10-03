package org.evochora.compiler.module;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.MachineInstructionInfo;
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
 * but not its code or the meaning of its tokens, so the sources hold the file once per inclusion,
 * each with the directive that made it, and the source map, the token map, the token lookup and
 * the instructions per line keep the placements apart. Programs are written to disk and compiled
 * through the whole pipeline.
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
        String main = resolved("main.evo");
        assertThat(artifact.sources())
                .extracting(SourceFile::placement, SourceFile::path, SourceFile::resolvedPath, SourceFile::instance,
                        SourceFile::includedAt)
                .containsExactly(
                        tuple("", "main.evo", main, 0, null),
                        tuple("FIRST", "lib/work.evo", lib, 0, new SourceInfo(main, 1, 1, "", 0)),
                        tuple("SECOND", "lib/work.evo", lib, 0, new SourceInfo(main, 2, 1, "", 0)));
        assertThat(artifact.sources().get(1).lines()).isSameAs(artifact.sources().get(2).lines());
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
        assertThat(artifact.tokenMap().get(new SourceInfo(lib, 4, 8, "SECOND", 0))).isEqualTo(second);
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
        assertThat(artifact.sourceLineToInstructions()).containsKeys("", "FIRST", "SECOND");
        assertThat(artifact.sourceLineToInstructions().get("FIRST").get(lib)).containsOnlyKeys(0);
        assertThat(artifact.sourceLineToInstructions().get("FIRST").get(lib).get(0)).containsKey(3);
        assertThat(artifact.sourceLineToInstructions().get("SECOND").get(lib).get(0).get(3)).hasSize(1);
        assertThat(artifact.sourceLineToInstructions().get("")).doesNotContainKey(lib);
        assertThat(artifact.sourceLineToInstructions().get("").get(resolved("main.evo")).get(0)).containsKey(5);
    }

    @Test
    void everyInclusionOfASourcedFileIsAnEntry_underThePlacementThatIncludesIt_withItsDirective() throws Exception {
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
        String main = resolved("main.evo");
        String mod = resolved("lib/mod.evo");
        assertThat(artifact.sources())
                .extracting(SourceFile::placement, SourceFile::path, SourceFile::resolvedPath, SourceFile::includedAt)
                .containsExactly(
                        tuple("", "main.evo", main, null),
                        tuple("", "lib/inc.evo", inc, new SourceInfo(main, 1, 1, "", 0)),
                        tuple("MOD", "lib/mod.evo", mod, new SourceInfo(main, 2, 1, "", 0)),
                        tuple("MOD", "lib/inc.evo", inc, new SourceInfo(mod, 1, 1, "MOD", 0)),
                        tuple("", "lib/inc.evo", inc, new SourceInfo(main, 3, 1, "", 0)));
        // The main file and the module placement are instance 0, every text inclusion one of its own
        assertThat(artifact.sources().get(0).instance()).isZero();
        assertThat(artifact.sources().get(2).instance()).isZero();
        assertThat(List.of(artifact.sources().get(1), artifact.sources().get(3), artifact.sources().get(4)))
                .extracting(SourceFile::instance)
                .doesNotHaveDuplicates()
                .allSatisfy(instance -> assertThat(instance).isPositive());
        // Every inclusion is an instance of its own, also the two in one placement, and the
        // instruction of each names its entry by placement, file and instance
        assertThat(artifact.sourceMap().values())
                .filteredOn(info -> info.fileName().equals(inc))
                .extracting(SourceInfo::placement, SourceInfo::expansion)
                .containsExactlyInAnyOrder(
                        tuple("", artifact.sources().get(1).instance()),
                        tuple("MOD", artifact.sources().get(3).instance()),
                        tuple("", artifact.sources().get(4).instance()));
        // The instructions per line keep the two inclusions in one placement apart: the entry of
        // each holds exactly the instruction its inclusion produced
        int first = artifact.sources().get(1).instance();
        int second = artifact.sources().get(4).instance();
        assertThat(artifact.sourceLineToInstructions().get("").get(inc)).containsOnlyKeys(first, second);
        assertThat(addressesOfLine(artifact, "", inc, first, 1)).containsExactly(addressOf(artifact, inc, "", first));
        assertThat(addressesOfLine(artifact, "", inc, second, 1)).containsExactly(addressOf(artifact, inc, "", second));
        assertThat(artifact.sourceLineToInstructions().get("MOD").get(inc))
                .containsOnlyKeys(artifact.sources().get(3).instance());
    }

    /**
     * Returns the linear addresses of the instructions listed under a line of an entry.
     */
    private static List<Integer> addressesOfLine(ProgramArtifact artifact, String placement, String file,
                                                 int instance, int line) {
        return artifact.sourceLineToInstructions().get(placement).get(file).get(instance).get(line).stream()
                .map(MachineInstructionInfo::linearAddress)
                .toList();
    }

    /**
     * Returns the linear address of the one instruction the source map places in a file, a
     * placement and an expansion.
     */
    private static int addressOf(ProgramArtifact artifact, String file, String placement, int expansion) {
        List<Integer> addresses = artifact.sourceMap().entrySet().stream()
                .filter(e -> e.getValue().fileName().equals(file) && e.getValue().placement().equals(placement)
                        && e.getValue().expansion() == expansion)
                .map(Map.Entry::getKey)
                .toList();
        assertThat(addresses).hasSize(1);
        return addresses.getFirst();
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
        // Each expansion stands on the lines of the inclusion of macros.evo in its own placement
        artifact.sourceMap().values().stream().filter(info -> info.fileName().equals(macros)).forEach(position -> {
            SourceFile home = artifact.sources().stream()
                    .filter(source -> source.placement().equals(position.placement())
                            && source.resolvedPath().equals(macros))
                    .findFirst()
                    .orElseThrow();
            assertThat(home.instance()).isPositive();
            assertThat(artifact.expansionHomes()).containsEntry(position.expansion(), home.instance());
            // The expansion's instruction stands under the body line in that entry
            assertThat(artifact.sourceLineToInstructions().get(position.placement()).get(macros))
                    .containsOnlyKeys(home.instance());
            assertThat(addressesOfLine(artifact, position.placement(), macros, home.instance(), 2))
                    .containsExactly(addressOf(artifact, macros, position.placement(), position.expansion()));
        });
        assertThat(artifact.expansionHomes()).hasSize(2);
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
