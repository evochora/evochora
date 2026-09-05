package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the molecule type STATE is available to assembly source.
 * <p>
 * STATE is registered like every other molecule type, so the compiler accepts it wherever a type
 * name is expected: in a {@code .PLACE} directive and as the type of a literal operand. Both
 * places must emit a molecule carrying {@link Config#TYPE_STATE}.
 */
@Tag("unit")
class StateMoleculeCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testPlaceEmitsAStateMolecule() throws CompilationException {
        String source = ".PLACE STATE:5 1|2";

        ProgramArtifact artifact = compiler.compile(List.of(source), "state_place.s", testEnvProps);

        assertThat(artifact.initialWorldObjects()).hasSize(1);
        PlacedMolecule placed = artifact.initialWorldObjects().values().iterator().next();
        assertThat(placed.type()).isEqualTo(Config.TYPE_STATE);
        assertThat(placed.value()).isEqualTo(5);
    }

    @Test
    void testTypedLiteralOperandEmitsAStateMolecule() throws CompilationException {
        String source = "SETI %DR0 STATE:5";

        ProgramArtifact artifact = compiler.compile(List.of(source), "state_literal.s", testEnvProps);

        assertThat(artifact.machineCodeLayout().values())
            .map(Molecule::fromInt)
            .anySatisfy(molecule -> {
                assertThat(molecule.type()).isEqualTo(Config.TYPE_STATE);
                assertThat(molecule.toScalarValue()).isEqualTo(5);
            });
    }
}
