package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.DependencyGraph;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.ModulePlacement;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lexer phase follows the branches of conditional blocks with the flags it has met, so
 * that the module graph holds exactly the modules of the branches the preprocessor keeps. A file
 * named in a branch that is not taken does not exist here, so loading it would be an error.
 */
@Tag("integration")
class ConditionalDependencyScanTest {

    @TempDir
    Path root;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @Test
    void theModuleOfATakenBranchIsInTheGraphAndThatOfASkippedOneIsNot() throws Exception {
        Files.writeString(root.resolve("big.evo"), "NOP\n");
        String main = String.join("\n",
                ".IFDEF BIG",
                "  .IMPORT \"big.evo\" AS LIB",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS LIB",
                ".ENDDEF",
                "NOP");

        Scan withBig = scan(main, Map.of("BIG", OptionalLong.empty()));
        Scan withoutBig = scan(String.join("\n",
                ".IFDEF BIG",
                "  .IMPORT \"missing.evo\" AS LIB",
                ".ENDDEF",
                "NOP"), Map.of());

        assertThat(withBig.diagnostics.hasErrors()).as(withBig.diagnostics.summary()).isFalse();
        assertThat(withBig.modules()).containsExactly("big.evo", "main.evo");
        assertThat(withoutBig.diagnostics.hasErrors()).as(withoutBig.diagnostics.summary()).isFalse();
        assertThat(withoutBig.modules()).containsExactly("main.evo");
    }

    @Test
    void theScanContinuesAfterTheEndOfABlockWhoseFirstBranchWasTaken() throws Exception {
        Files.writeString(root.resolve("first.evo"), "NOP\n");
        Files.writeString(root.resolve("after.evo"), "NOP\n");
        String main = String.join("\n",
                ".DEFINE A",
                ".IFDEF A",
                "  .IMPORT \"first.evo\" AS FIRST",
                ".ELSEIFDEF B",
                "  .IMPORT \"missing.evo\" AS FIRST",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS FIRST",
                ".ENDDEF",
                ".IMPORT \"after.evo\" AS AFTER",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactlyInAnyOrder("first.evo", "after.evo", "main.evo");
    }

    @Test
    void elsedefIsTakenAfterASkippedBranchWithANestedBlock() throws Exception {
        Files.writeString(root.resolve("other.evo"), "NOP\n");
        String main = String.join("\n",
                ".IFDEF A",
                "  .IFDEF B",
                "    .IMPORT \"missing.evo\" AS X",
                "  .ELSEDEF",
                "    .IMPORT \"missing.evo\" AS X",
                "  .ENDDEF",
                ".ELSEDEF",
                "  .IMPORT \"other.evo\" AS X",
                ".ENDDEF",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactly("other.evo", "main.evo");
    }

    @Test
    void aDefineDecidesALaterBlockAndADefineInASkippedBranchDoesNot() throws Exception {
        Files.writeString(root.resolve("level.evo"), "NOP\n");
        String main = String.join("\n",
                ".IFDEF NEVER",
                "  .DEFINE GHOST",
                ".ENDDEF",
                ".IFDEF GHOST",
                "  .IMPORT \"missing.evo\" AS X",
                ".ENDDEF",
                ".DEFINE LEVEL 0x2",
                ".IFDEF LEVEL >= 2",
                "  .IMPORT \"level.evo\" AS L",
                ".ENDDEF",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactly("level.evo", "main.evo");
    }

    @Test
    void bothSpellingsOfEqualityAndInequalityAreRead() throws Exception {
        Files.writeString(root.resolve("equal.evo"), "NOP\n");
        Files.writeString(root.resolve("unequal.evo"), "NOP\n");
        String main = String.join("\n",
                ".DEFINE LEVEL 2",
                ".IFDEF LEVEL == 2",
                "  .IMPORT \"equal.evo\" AS E",
                ".ENDDEF",
                ".IFDEF LEVEL != 3",
                "  .IMPORT \"unequal.evo\" AS U",
                ".ENDDEF",
                ".IFDEF LEVEL != 2",
                "  .IMPORT \"missing.evo\" AS M",
                ".ENDDEF",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactlyInAnyOrder("equal.evo", "unequal.evo", "main.evo");
    }

    @Test
    void aConditionThatCannotBeReadSkipsTheWholeBlockAndIsNotReported() throws Exception {
        String main = String.join("\n",
                ".IFDEF",
                "  .IMPORT \"missing.evo\" AS X",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS X",
                ".ENDDEF",
                ".IFDEF UNSET",
                "  .IMPORT \"missing.evo\" AS X",
                ".ELSEIFDEF A B",
                "  .IMPORT \"missing.evo\" AS X",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS X",
                ".ENDDEF",
                ".IFDEF A B",
                "  .IMPORT \"missing.evo\" AS X",
                ".ENDDEF",
                ".DEFINE A",
                ".IFDEF A > 1",
                "  .IMPORT \"missing.evo\" AS X",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS X",
                ".ENDDEF",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactly("main.evo");
    }

    @Test
    void anUndefRemovesTheFlagForTheScan() throws Exception {
        String main = String.join("\n",
                ".UNDEF A",
                ".IFDEF A",
                "  .IMPORT \"missing.evo\" AS X",
                ".ENDDEF",
                "NOP");

        Scan result = scan(main, Map.of("A", OptionalLong.empty()));

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactly("main.evo");
    }

    @Test
    void aStrayEndInAnImportedModuleDoesNotCloseTheImportersBlock() throws Exception {
        Files.writeString(root.resolve("stray.evo"), "NOP\n.ENDDEF\n");
        String main = String.join("\n",
                ".IFNDEF X",
                "  .IMPORT \"stray.evo\" AS STRAY",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS X",
                ".ENDDEF",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactly("stray.evo", "main.evo");
    }

    /**
     * A head with a word before it breaks the line rule, and the preprocessor removes the whole
     * block; the scan therefore follows none of its branches, whatever the condition says.
     */
    @Test
    void aHeadWithAWordBeforeItIsInvalidAndNoBranchOfItsBlockIsScanned() throws Exception {
        for (Map<String, OptionalLong> defines : List.of(Map.<String, OptionalLong>of(), Map.of("X", OptionalLong.empty()))) {
            Scan result = scan(String.join("\n",
                    "L: .IFDEF X",
                    "  .IMPORT \"missing.evo\" AS M",
                    ".ELSEDEF",
                    "  .IMPORT \"missing.evo\" AS M",
                    ".ENDDEF",
                    "NOP"), defines);

            assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
            assertThat(result.modules()).containsExactly("main.evo");
        }
    }

    /**
     * A word that only ends in the text of a conditional directive, or one inside a string, is no
     * head; the line is not the scan's business.
     */
    @Test
    void aDirectiveWordInsideAnotherWordOrAStringIsNoHead() throws Exception {
        Files.writeString(root.resolve("x .ifdef y.evo"), "NOP\n");
        Scan result = scan(String.join("\n",
                ".SOURCE \"x .ifdef y.evo\"",
                "LIB.IFDEF X",
                "NOP"), Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
    }

    @Test
    void aModuleImportedTwiceIsScannedAtEachImportWithTheFlagsOfThatImport() throws Exception {
        Files.writeString(root.resolve("big.evo"), "NOP\n");
        Files.writeString(root.resolve("small.evo"), "NOP\n");
        Files.writeString(root.resolve("module.evo"), String.join("\n",
                ".IFDEF BIG",
                "  .IMPORT \"big.evo\" AS IMPL",
                ".ELSEDEF",
                "  .IMPORT \"small.evo\" AS IMPL",
                ".ENDDEF",
                "NOP"));
        String main = String.join("\n",
                ".DEFINE BIG",
                ".IMPORT \"module.evo\" AS FIRST",
                ".UNDEF BIG",
                ".IMPORT \"module.evo\" AS SECOND",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactly("big.evo", "module.evo", "small.evo", "module.evo", "main.evo");
        assertThat(result.graph.placements()).extracting(ModulePlacement::aliasChain)
                .containsExactly("FIRST.IMPL", "FIRST", "SECOND.IMPL", "SECOND", "");
    }

    @Test
    void theScanFollowsAComputedConditionAsThePreprocessorDoes() throws Exception {
        Files.writeString(root.resolve("three.evo"), "NOP\n");
        String block = String.join("\n",
                ".IFDEF (A+1) = 3",
                "  .IMPORT \"three.evo\" AS LIB",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS LIB",
                ".ENDDEF",
                "NOP");

        Scan withA = scan(".DEFINE A 2\n" + block, Map.of());
        Scan withOtherA = scan(".DEFINE A 5\n" + String.join("\n",
                ".IFDEF (A+1) = 3",
                "  .IMPORT \"missing.evo\" AS LIB",
                ".ENDDEF",
                "NOP"), Map.of());

        assertThat(withA.diagnostics.hasErrors()).as(withA.diagnostics.summary()).isFalse();
        assertThat(withA.modules()).containsExactly("three.evo", "main.evo");
        assertThat(withOtherA.diagnostics.hasErrors()).as(withOtherA.diagnostics.summary()).isFalse();
        assertThat(withOtherA.modules()).containsExactly("main.evo");
    }

    @Test
    void aConditionThatCannotBeComputedSkipsTheWholeBlockSilently() throws Exception {
        String main = String.join("\n",
                ".IFDEF (A+1) = 3",
                "  .IMPORT \"missing.evo\" AS LIB",
                ".ELSEDEF",
                "  .IMPORT \"missing.evo\" AS LIB",
                ".ENDDEF",
                "NOP");

        Scan result = scan(main, Map.of());

        assertThat(result.diagnostics.hasErrors()).as(result.diagnostics.summary()).isFalse();
        assertThat(result.modules()).containsExactly("main.evo");
    }

    private record Scan(DependencyGraph graph, DiagnosticsEngine diagnostics) {
        /** The file names of the placements, each after the placements it imports. */
        List<String> modules() {
            return graph.placements().stream()
                    .map(ModulePlacement::sourcePath)
                    .map(path -> Path.of(path).getFileName().toString())
                    .toList();
        }
    }

    private Scan scan(String mainSource, Map<String, OptionalLong> defines) {
        FeatureRegistry features = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(features));
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(".", null)), defines, Map.of());
        Lexer lexer = new Lexer(diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), root),
                features.dependencyScanHandlers(), options, new RuntimeInstructionSetAdapter(), features.lexerSymbols(),
                features.operations(), Flags.of(options.defines(), features.operations().reservedNames()));
        String mainPath = root.resolve("main.evo").toString();
        DependencyGraph graph = lexer.read(mainSource + "\n", mainPath, "");
        return new Scan(graph, diagnostics);
    }
}
