package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.CompilerTestBase;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test that compiles the primordial assembly and verifies
 * the structural completeness of the resulting ProgramArtifact.
 */
@Tag("unit")
class AnnotationDiagnosticTest extends CompilerTestBase {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void diagnoseRealCompilation() throws Exception {
        String assemblyRoot = Path.of("").toAbsolutePath().resolve("assembly/primordial").toString();
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(assemblyRoot, null)));

        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile("main.evo", testEnvProps, options);

        assertNotNull(artifact.programId(), "programId must be present");
        assertNotNull(artifact.sources(), "sources must be present");
        assertFalse(artifact.sources().isEmpty(), "sources must have entries");
        assertNotNull(artifact.sourceMap(), "sourceMap must be present");
        assertFalse(artifact.sourceMap().isEmpty(), "sourceMap must have entries");
        assertNotNull(artifact.relativeCoordToLinearAddress(), "relativeCoordToLinearAddress must be present");
        assertNotNull(artifact.tokenLookup(), "tokenLookup must be present");
        assertFalse(artifact.tokenLookup().isEmpty(), "tokenLookup must have entries");
    }
}
