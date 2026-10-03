package org.evochora.compiler.backend;

import org.evochora.compiler.api.CompilationException;
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
 * The emitter lists every instruction under the source entry its position names, and reports a
 * position that names no entry as a defect of the compiler.
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
        ProgramArtifact artifact = emit(new SourceInfo(MAIN, 1, 1, "", 0));

        assertThat(artifact.sourceLineToInstructions().get("").get(MAIN).get(0).get(1)).hasSize(1);
    }

    @Test
    void anInstructionWhosePositionNamesNoEntryIsAnInternalError() {
        assertThatThrownBy(() -> emit(new SourceInfo(MAIN, 1, 1, "", 5)))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Internal error")
                .hasMessageContaining("names no source entry");
    }

    /**
     * Lays out and emits a program of one {@code NOP} at the given position, with the main file as
     * its only source entry.
     */
    private static ProgramArtifact emit(SourceInfo position) throws Exception {
        RuntimeInstructionSetAdapter isa = new RuntimeInstructionSetAdapter();
        IrProgram program = new IrProgram("Test", List.of(new IrInstruction("NOP", List.of(), position)),
                new DebugInfo(List.of(MAIN_ENTRY), Map.of(), Map.of(), Map.of()));
        LayoutResult layout = new LayoutEngine().layout(program, isa,
                new EnvironmentProperties(new int[]{10, 10}, true), new LayoutDirectiveRegistry((directive, context) -> { }));
        return new Emitter().emit(program, layout, new LinkingContext(isa), isa, new EmissionContributorRegistry());
    }
}
