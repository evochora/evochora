package org.evochora.compiler.features.conditional;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.Expansion;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceFile.LeftOut;
import org.evochora.compiler.api.SourceFile.Note;
import org.evochora.compiler.api.SourceInfo;
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
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a conditional block records for the source view: the region of every branch it left out,
 * owned by the branch's directive line, and the state of every flag its heads name, per
 * placement and per instance of injected tokens: a macro expansion or a {@code .SOURCE}
 * inclusion. Programs are written to disk and compiled through the whole
 * pipeline, and the records are read from the artifact's sources.
 */
@Tag("integration")
class LeftOutBranchesTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void eachPlacementFoldsTheBranchItLeftOut_andNotesTheValueOfItsFlag() throws Exception {
        write("lib.evo",
                "EXPORT .PROC WORK",
                ".IFDEF PAD >= 3",
                "  NOP",
                ".ELSEDEF",
                "  NOP",
                ".ENDDEF",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".DEFINE PAD 1",
                ".IMPORT \"lib.evo\" AS FIRST",
                ".UNDEF PAD",
                ".DEFINE PAD 3",
                ".IMPORT \"lib.evo\" AS SECOND",
                "START:",
                "  CALL FIRST.WORK",
                "  CALL SECOND.WORK");

        ProgramArtifact artifact = compile(Map.of());

        SourceFile first = source(artifact, "FIRST", "lib.evo");
        SourceFile second = source(artifact, "SECOND", "lib.evo");
        assertThat(first.leftOut()).containsExactly(new LeftOut(0, 2, 3, 3));
        assertThat(first.notes()).containsExactly(new Note(0, 2, 8, "[=1]"));
        assertThat(second.leftOut()).containsExactly(new LeftOut(0, 4, 5, 5));
        assertThat(second.notes()).containsExactly(new Note(0, 2, 8, "[=3]"));
        assertThat(source(artifact, "", "main.evo").leftOut()).isEmpty();
    }

    @Test
    void anIfndefOfAnUnsetFlagNotesNotSet_andTheElseHasNoNote() throws Exception {
        write("main.evo",
                "START:",
                ".IFNDEF PAD",
                "  NOP",
                ".ELSEDEF",
                "  NOP",
                "  NOP",
                ".ENDDEF");

        SourceFile main = source(compile(Map.of()), "", "main.evo");

        assertThat(main.leftOut()).containsExactly(new LeftOut(0, 4, 5, 6));
        assertThat(main.notes()).containsExactly(new Note(0, 2, 9, "[not set]"));
    }

    @Test
    void aFlagWithoutValueNotesSet() throws Exception {
        write("main.evo",
                "START:",
                ".IFDEF PAD",
                "  NOP",
                ".ENDDEF");

        SourceFile main = source(compile(Map.of("PAD", OptionalInt.empty())), "", "main.evo");

        assertThat(main.leftOut()).isEmpty();
        assertThat(main.notes()).containsExactly(new Note(0, 2, 8, "[set]"));
    }

    @Test
    void aFlagOnTheRightOfAComparisonIsNoted_andHeadsAfterTheTakenOneToo() throws Exception {
        write("main.evo",
                "START:",
                ".IFDEF PAD > LIMIT",
                "  NOP",
                ".ELSEIFDEF EXTRA",
                "  NOP",
                ".ENDDEF");

        SourceFile main = source(compile(Map.of("PAD", OptionalInt.of(3), "LIMIT", OptionalInt.of(2))),
                "", "main.evo");

        assertThat(main.leftOut()).containsExactly(new LeftOut(0, 4, 5, 5));
        assertThat(main.notes()).containsExactly(
                new Note(0, 2, 8, "[=3]"),
                new Note(0, 2, 14, "[=2]"),
                new Note(0, 4, 12, "[not set]"));
    }

    @Test
    void anEmptyBranchLeftOutRecordsNoRegion() throws Exception {
        write("main.evo",
                "START:",
                ".IFDEF PAD",
                ".ELSEDEF",
                "  NOP",
                ".ENDDEF");

        SourceFile main = source(compile(Map.of()), "", "main.evo");

        assertThat(main.leftOut()).isEmpty();
        assertThat(main.notes()).containsExactly(new Note(0, 2, 8, "[not set]"));
    }

    @Test
    void aNestedBlockInABranchLeftOutRecordsNothingOfItsOwn() throws Exception {
        write("main.evo",
                "START:",
                ".IFDEF OUTER",
                ".IFDEF INNER",
                "  NOP",
                ".ENDDEF",
                ".ENDDEF",
                "  NOP");

        SourceFile main = source(compile(Map.of("INNER", OptionalInt.empty())), "", "main.evo");

        assertThat(main.leftOut()).containsExactly(new LeftOut(0, 2, 3, 5));
        assertThat(main.notes()).containsExactly(new Note(0, 2, 8, "[not set]"));
    }

    /**
     * A parameter as the flag name lets every expansion of the macro decide on its own; each
     * expansion records the branch it left out under its own number, the instructions it kept
     * carry that number in the source map, and the note on the flag stands both on the argument
     * at the call site and on the parameter in the body of that expansion.
     */
    @Test
    void twoExpansionsOfAMacroFoldTheirOwnBranches_andTheirInstructionsCarryTheirExpansion() throws Exception {
        write("main.evo",
                ".MACRO CHECK FLAG",
                ".IFDEF FLAG",
                "  NOP",
                ".ELSEDEF",
                "  NOP",
                ".ENDDEF",
                ".ENDMACRO",
                "START:",
                "  CHECK ON",
                "  CHECK OFF");

        ProgramArtifact artifact = compile(Map.of("ON", OptionalInt.empty()));

        String main = resolved("main.evo");
        int kept = expansionOfLine(artifact, main, 3);
        int other = expansionOfLine(artifact, main, 5);
        assertThat(kept).isPositive();
        assertThat(other).isPositive().isNotEqualTo(kept);
        SourceFile source = source(artifact, "", "main.evo");
        assertThat(source.leftOut()).isEmpty();
        assertThat(source.notes()).containsExactly(
                new Note(0, 9, 9, "[set]"),
                new Note(0, 10, 9, "[not set]"));
        assertThat(artifact.expansions().get(kept).leftOut()).containsExactly(new LeftOut(kept, 4, 5, 5));
        assertThat(artifact.expansions().get(kept).notes()).containsExactly(new Note(kept, 2, 8, "[set]"));
        assertThat(artifact.expansions().get(other).leftOut()).containsExactly(new LeftOut(other, 2, 3, 3));
        assertThat(artifact.expansions().get(other).notes()).containsExactly(new Note(other, 2, 8, "[not set]"));
    }

    /**
     * An argument passed on by an outer macro to an inner one replaces a parameter at each level;
     * the note on it stands at the call, at the parameter of the outer body and at the parameter
     * of the inner body, each in the expansion it belongs to.
     */
    @Test
    void aFlagPassedThroughNestedMacrosIsNotedAtEveryLevel() throws Exception {
        write("main.evo",
                ".MACRO CHECK FLAG",
                ".IFDEF FLAG",
                "  NOP",
                ".ELSEDEF",
                "  NOP",
                ".ENDDEF",
                ".ENDMACRO",
                ".MACRO OUTER NAME",
                "  CHECK NAME",
                ".ENDMACRO",
                "START:",
                "  OUTER ON");

        ProgramArtifact artifact = compile(Map.of("ON", OptionalInt.of(2)));

        SourceFile source = source(artifact, "", "main.evo");
        assertThat(expansionOfLine(artifact, resolved("main.evo"), 3)).isEqualTo(2);
        assertThat(source.leftOut()).isEmpty();
        assertThat(source.notes()).containsExactly(new Note(0, 12, 9, "[=2]"));
        assertThat(artifact.expansions().get(1).notes()).containsExactly(new Note(1, 9, 9, "[=2]"));
        assertThat(artifact.expansions().get(2).leftOut()).containsExactly(new LeftOut(2, 4, 5, 5));
        assertThat(artifact.expansions().get(2).notes()).containsExactly(new Note(2, 2, 8, "[=2]"));
    }

    /**
     * The copies of a repeated block stand in one expansion and decide alike; they record one
     * region and one note, not one per copy.
     */
    @Test
    void theCopiesOfARepeatedBlockShareTheExpansionTheyStandIn() throws Exception {
        write("main.evo",
                ".MACRO TWICE",
                ".REPEAT 2",
                ".IFDEF PAD",
                "  NOP",
                ".ENDDEF",
                "  NOP",
                ".ENDREPEAT",
                ".ENDMACRO",
                "START:",
                "  TWICE");

        ProgramArtifact artifact = compile(Map.of());

        int expansion = expansionOfLine(artifact, resolved("main.evo"), 6);
        assertThat(expansion).isPositive();
        assertThat(source(artifact, "", "main.evo").leftOut()).isEmpty();
        assertThat(artifact.expansions().get(expansion).leftOut()).containsExactly(new LeftOut(expansion, 3, 4, 4));
        assertThat(artifact.expansions().get(expansion).notes()).containsExactly(new Note(expansion, 3, 8, "[not set]"));
    }

    /**
     * A file sourced twice into one placement behind an include guard is two inclusions and two
     * entries, each with an instance of its own and the position of its directive: the first
     * keeps its block and notes the flag unset, the second leaves the block out and notes the
     * flag set, and the instructions the first kept carry its instance.
     */
    @Test
    void eachInclusionOfAGuardedFileIsAnEntryWithItsOwnRecords() throws Exception {
        write("lib.evo",
                ".IFNDEF LIB",
                ".DEFINE LIB",
                "  NOP",
                ".ENDDEF");
        write("util.evo",
                ".SOURCE \"lib.evo\"");
        write("main.evo",
                "START:",
                ".SOURCE \"lib.evo\"",
                ".SOURCE \"util.evo\"");

        ProgramArtifact artifact = compile(Map.of());

        String lib = resolved("lib.evo");
        List<SourceFile> inclusions = artifact.sources().stream()
                .filter(source -> source.resolvedPath().equals(lib))
                .toList();
        assertThat(inclusions).hasSize(2);
        SourceFile first = inclusions.get(0);
        SourceFile second = inclusions.get(1);
        assertThat(first.includedAt()).isEqualTo(new SourceInfo(resolved("main.evo"), 2, 1, "", 0));
        assertThat(second.includedAt().fileName()).isEqualTo(resolved("util.evo"));
        assertThat(second.includedAt().lineNumber()).isEqualTo(1);
        assertThat(first.instance()).isPositive().isEqualTo(expansionOfLine(artifact, lib, 3));
        assertThat(second.instance()).isPositive().isNotEqualTo(first.instance());
        assertThat(first.notes()).containsExactly(new Note(first.instance(), 1, 9, "[not set]"));
        assertThat(first.leftOut()).isEmpty();
        assertThat(second.notes()).containsExactly(new Note(second.instance(), 1, 9, "[set]"));
        assertThat(second.leftOut()).containsExactly(new LeftOut(second.instance(), 1, 2, 3));
    }

    /**
     * A macro expansion is no entry: the regions and notes of an expansion of a macro defined in
     * a sourced file stand on the expansion, under its number, not on the entry of the inclusion;
     * the expansion names its call, its definition in that inclusion and its name.
     */
    @Test
    void theRecordsOfAMacroExpansionStandOnTheExpansion() throws Exception {
        write("macros.evo",
                ".MACRO CHECK",
                ".IFDEF PAD",
                "  NOP",
                ".ENDDEF",
                ".ENDMACRO");
        write("main.evo",
                ".SOURCE \"macros.evo\"",
                "START:",
                "  CHECK",
                "  NOP");

        ProgramArtifact artifact = compile(Map.of());

        SourceFile macros = source(artifact, "", "macros.evo");
        assertThat(macros.leftOut()).isEmpty();
        assertThat(macros.notes()).isEmpty();
        assertThat(artifact.expansions()).hasSize(1);
        int number = artifact.expansions().keySet().iterator().next();
        Expansion expansion = artifact.expansions().get(number);
        assertThat(expansion.name()).isEqualTo("CHECK");
        assertThat(expansion.calledAt()).isEqualTo(new SourceInfo(resolved("main.evo"), 3, 3, "", 0));
        assertThat(expansion.definedAt()).isEqualTo(new SourceInfo(resolved("macros.evo"), 1, 8, "", macros.instance()));
        assertThat(expansion.leftOut()).containsExactly(new LeftOut(number, 2, 3, 3));
        assertThat(expansion.notes()).containsExactly(new Note(number, 2, 8, "[not set]"));
        assertThat(source(artifact, "", "main.evo").leftOut()).isEmpty();
    }

    /**
     * A flag name passed to a macro whose body defines another macro that tests it is noted at the
     * argument where it was written and at the parameter in the body, once in the outer expansion,
     * where the definition was read, and once in the inner one, where the block was decided; the
     * instruction the inner expansion kept stands under its own number.
     */
    @Test
    void aFlagPassedIntoADefinitionInABodyIsNotedInBothExpansions() throws Exception {
        write("main.evo",
                ".MACRO OUTER F",
                "  .MACRO INNER",
                "    .IFDEF F",
                "      NOP",
                "    .ENDDEF",
                "  .ENDMACRO",
                "  INNER",
                ".ENDMACRO",
                "START:",
                "  OUTER ON");

        ProgramArtifact artifact = compile(Map.of("ON", OptionalInt.empty()));

        String main = resolved("main.evo");
        assertThat(source(artifact, "", "main.evo").notes()).containsExactly(new Note(0, 10, 9, "[set]"));
        assertThat(artifact.expansions()).containsOnlyKeys(1, 2);
        assertThat(artifact.expansions().get(1).notes()).containsExactly(new Note(1, 3, 12, "[set]"));
        assertThat(artifact.expansions().get(2).notes()).containsExactly(new Note(2, 3, 12, "[set]"));
        assertThat(artifact.sourceLineToInstructions().get("").get(main)).containsOnlyKeys(2);
        assertThat(artifact.sourceLineToInstructions().get("").get(main).get(2)).containsOnlyKeys(4);
    }

    /**
     * Returns the one expansion the instructions of a line were compiled in.
     */
    private static int expansionOfLine(ProgramArtifact artifact, String file, int line) {
        List<Integer> expansions = artifact.sourceMap().values().stream()
                .filter(info -> info.fileName().equals(file) && info.lineNumber() == line)
                .map(SourceInfo::expansion)
                .distinct()
                .toList();
        assertThat(expansions).hasSize(1);
        return expansions.getFirst();
    }

    private static SourceFile source(ProgramArtifact artifact, String placement, String path) {
        return artifact.sources().stream()
                .filter(source -> source.placement().equals(placement) && source.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private String resolved(String fileName) {
        return sourceRoot.resolve(fileName).toString().replace('\\', '/');
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile(Map<String, OptionalInt> defines) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), defines);
        return new Compiler().compile("main.evo", ENV, options);
    }
}
