package org.evochora.compiler.backend;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.Expansion;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.emit.EmissionContributorRegistry;
import org.evochora.compiler.backend.emit.Emitter;
import org.evochora.compiler.backend.layout.LayoutDirectiveRegistry;
import org.evochora.compiler.backend.layout.LayoutEngine;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.ir.DebugInfo;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The emitter lists every instruction under the placement, file and expansion of its position;
 * a position must name an entry, directly or through the definitions of the expansions it stands
 * in, and one that does not is reported as a defect of the compiler.
 */
@Tag("unit")
class EmitterTest {

    private static final String MAIN = "/p/main.evo";
    private static final SourceFile MAIN_ENTRY = new SourceFile("", "main.evo", MAIN, List.of("NOP"));

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void anInstructionIsListedUnderTheEntryItsPositionNames() throws Exception {
        ProgramArtifact artifact = emit(new SourceInfo(MAIN, 1, 1, "", 0), Map.of());

        assertThat(artifact.sourceLineToInstructions().get("").get(MAIN).get(0).get(1)).hasSize(1);
    }

    /**
     * Expansion 2 is of a macro defined in the body of expansion 1, whose macro is defined in the
     * main file: the instruction stands under expansion 2, and its position leads to the main
     * file's entry through both definitions.
     */
    @Test
    void anInstructionOfANestedExpansionIsListedUnderItsExpansion() throws Exception {
        Map<Integer, Expansion> expansions = Map.of(
                1, expansion(new SourceInfo(MAIN, 1, 8, "", 0)),
                2, expansion(new SourceInfo(MAIN, 2, 10, "", 1)));

        ProgramArtifact artifact = emit(new SourceInfo(MAIN, 3, 5, "", 2), expansions);

        assertThat(artifact.sourceLineToInstructions().get("").get(MAIN)).containsOnlyKeys(2);
        assertThat(artifact.sourceLineToInstructions().get("").get(MAIN).get(2).get(3)).hasSize(1);
    }

    /**
     * Expansion 3 is of a macro whose definition stands in a file that is no entry: the
     * definitions lead nowhere, and the instruction is a defect of the compiler.
     */
    @Test
    void anInstructionOfAnExpansionWhoseDefinitionLeadsToNoEntryIsAnInternalError() {
        Map<Integer, Expansion> expansions = Map.of(3, expansion(new SourceInfo("/p/other.evo", 1, 8, "", 0)));

        assertThatThrownBy(() -> emit(new SourceInfo("/p/other.evo", 2, 3, "", 3), expansions))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Internal error")
                .hasMessageContaining("names no source entry");
    }

    @Test
    void anInstructionWhosePositionNamesNoEntryIsAnInternalError() {
        assertThatThrownBy(() -> emit(new SourceInfo(MAIN, 1, 1, "", 5), Map.of()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Internal error")
                .hasMessageContaining("names no source entry");
    }

    /**
     * Returns an expansion of a macro defined at the given position, called on the first line.
     */
    private static Expansion expansion(SourceInfo definedAt) {
        return new Expansion(new SourceInfo(MAIN, 1, 1, "", 0), definedAt, "M", List.of(), List.of(), List.of());
    }

    /**
     * Lays out and emits a program of one {@code NOP} at the given position, with the main file as
     * its only source entry and the given expansions.
     */
    private static ProgramArtifact emit(SourceInfo position, Map<Integer, Expansion> expansions) throws Exception {
        RuntimeInstructionSetAdapter isa = new RuntimeInstructionSetAdapter();
        IrProgram program = new IrProgram("Test", List.of(new IrInstruction("NOP", List.of(), position)),
                new DebugInfo(List.of(MAIN_ENTRY), expansions, Map.of(), Map.of()));
        LayoutResult layout = new LayoutEngine().layout(program, isa,
                new EnvironmentProperties(new int[]{10, 10}, true), new LayoutDirectiveRegistry((directive, context) -> { }));
        return new Emitter().emit(program, layout, new LinkingContext(isa), isa, new EmissionContributorRegistry());
    }
}
