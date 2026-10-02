package org.evochora.compiler.backend.emit;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the program ID the emitter assigns: a digest of the machine code, the initial objects
 * and the flags of the compilation that depends on which value stands at which coordinate.
 */
@Tag("unit")
class ProgramIdentityTest {

    /**
     * The ID of {@link #PINNED_PROGRAM}. It changes only when the digest procedure of
     * {@link ProgramIdentity} changes; such a change gives every program a new ID.
     */
    private static final String PINNED_ID = "c15626bec2500bde";

    private static final List<String> PINNED_PROGRAM = List.of(
            "NOP",
            "SETI %DR0 DATA:1",
            ".PLACE ENERGY:7 5|5");

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{96, 96}, true);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void theIdOfAFixedProgramIsPinned() throws CompilationException {
        assertThat(compile(PINNED_PROGRAM).programId()).isEqualTo(PINNED_ID);
    }

    @Test
    void theIdIsSixteenLowerCaseHexCharacters() throws CompilationException {
        assertThat(compile(PINNED_PROGRAM).programId()).matches("[0-9a-f]{16}");
    }

    @Test
    void programsWithTheSameCodeAndOtherPlacedObjectsHaveDifferentIds() throws CompilationException {
        ProgramArtifact first = compile(List.of(
                "NOP",
                ".PLACE DATA:1 5|5",
                ".PLACE ENERGY:1 6|6"));
        ProgramArtifact swapped = compile(List.of(
                "NOP",
                ".PLACE ENERGY:1 5|5",
                ".PLACE DATA:1 6|6"));

        assertThat(first.machineCodeLayout().values())
                .containsExactlyElementsOf(swapped.machineCodeLayout().values());
        assertThat(first.programId()).isNotEqualTo(swapped.programId());
    }

    @Test
    void programsWithTheSameInstructionsInAnotherOrderHaveDifferentIds() throws CompilationException {
        ProgramArtifact first = compile(List.of(
                "SETI %DR0 DATA:1",
                "SETI %DR1 DATA:2"));
        ProgramArtifact reordered = compile(List.of(
                "SETI %DR1 DATA:2",
                "SETI %DR0 DATA:1"));

        assertThat(first.machineCodeLayout().values())
                .containsExactlyInAnyOrderElementsOf(reordered.machineCodeLayout().values());
        assertThat(first.programId()).isNotEqualTo(reordered.programId());
    }

    /**
     * Flags that change no code still make another variant: its artifact folds other branches
     * and notes other flag states, so it must not share the ID, and with it the artifact, of the
     * variant compiled without them.
     */
    @Test
    void compilationsWithOtherFlagsAndTheSameCodeHaveDifferentIds() throws CompilationException {
        ProgramArtifact plain = compile(PINNED_PROGRAM, Map.of());
        ProgramArtifact flagged = compile(PINNED_PROGRAM, Map.of("UNUSED", OptionalInt.empty()));
        ProgramArtifact valued = compile(PINNED_PROGRAM, Map.of("UNUSED", OptionalInt.of(0)));

        assertThat(flagged.machineCodeLayout().values()).containsExactlyElementsOf(plain.machineCodeLayout().values());
        assertThat(valued.machineCodeLayout().values()).containsExactlyElementsOf(plain.machineCodeLayout().values());
        assertThat(List.of(plain.programId(), flagged.programId(), valued.programId())).doesNotHaveDuplicates();
    }

    @Test
    void theSameProgramCompiledTwiceHasTheSameId() throws CompilationException {
        assertThat(compile(PINNED_PROGRAM).programId()).isEqualTo(compile(PINNED_PROGRAM).programId());
    }

    private static ProgramArtifact compile(List<String> lines) throws CompilationException {
        return new Compiler().compile(lines, "identity.evo", ENV);
    }

    private static ProgramArtifact compile(List<String> lines, Map<String, OptionalInt> defines)
            throws CompilationException {
        return new Compiler().compile(lines, "identity.evo", ENV,
                new CompilerOptions(List.of(new SourceRoot(".", null)), defines));
    }
}
