package org.evochora.compiler;

import java.nio.file.Path;
import java.util.List;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles the assembly programs kept in this repository.
 * <p>
 * The examples are documentation that has to work: the reference names them by file and gives the
 * command line that translates them, so a reader is entitled to expect that it does. The primordial
 * organism is what the simulation actually runs. Both reach further than the unit tests around the
 * module system, which build their modules in a temporary directory — these resolve paths through a
 * source root and nest modules several levels deep, which is where module resolution is easiest to
 * break without noticing.
 */
class AssemblyProgramsCompileTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @ParameterizedTest(name = "{1} from {0}")
    @CsvSource({
            "assembly/examples,   simple.evo",
            "assembly/examples,   complex.evo",
            "assembly/examples,   modules.evo",
            "assembly/primordial, main.evo",
    })
    @Tag("integration")
    void aProgramInThisRepositoryCompiles(String sourceRootPath, String fileName) throws Exception {
        String sourceRoot = Path.of("").toAbsolutePath().resolve(sourceRootPath).toString();
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot, null)));

        ProgramArtifact artifact = new Compiler().compile(fileName, ENV, options);

        assertThat(artifact).isNotNull();
        assertThat(artifact.machineCodeLayout())
                .as("%s produces machine code", fileName)
                .isNotEmpty();
    }
}
