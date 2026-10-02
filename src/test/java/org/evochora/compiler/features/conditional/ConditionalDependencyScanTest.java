package org.evochora.compiler.features.conditional;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.util.SourceRootResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dependency scan follows the branches of conditional blocks with the flags it has met, so
 * that the module graph holds exactly the modules of the branches the preprocessor keeps. A file
 * named in a branch that is not taken does not exist here, so loading it would be an error.
 */
@Tag("integration")
class ConditionalDependencyScanTest {

    @TempDir
    Path root;

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

        Scan withBig = scan(main, Map.of("BIG", OptionalInt.empty()));
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

        Scan result = scan(main, Map.of("A", OptionalInt.empty()));

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

    private record Scan(DependencyGraph graph, DiagnosticsEngine diagnostics) {
        /** The file names of the modules, in topological order. */
        List<String> modules() {
            return graph.topologicalOrder().stream()
                    .map(ModuleDescriptor::sourcePath)
                    .map(path -> Path.of(path).getFileName().toString())
                    .toList();
        }
    }

    private Scan scan(String mainSource, Map<String, OptionalInt> defines) {
        FeatureRegistry features = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(features));
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(".", null)), defines);
        DependencyScanner scanner = new DependencyScanner(diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), root),
                features.dependencyScanHandlers(), options);
        DependencyGraph graph = scanner.scan(mainSource + "\n", root.resolve("main.evo").toString());
        return new Scan(graph, diagnostics);
    }
}
