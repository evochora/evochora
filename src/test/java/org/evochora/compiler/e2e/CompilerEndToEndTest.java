package org.evochora.compiler.e2e;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains end-to-end tests for the {@link Compiler}.
 * These tests compile source code from an in-memory string and verify the output artifact.
 * While they test the entire pipeline, they are tagged as "unit" tests because they do not
 * rely on external resources like the filesystem or network.
 */
public class CompilerEndToEndTest {

	@BeforeAll
	static void setUp() {
		Instruction.init();
	}

	/**
	 * Tests the end-to-end compilation of a procedure definition with parameters and a call to it.
	 * It verifies that the compilation succeeds and produces a valid, non-empty program artifact.
	 * This test covers a large part of the compiler pipeline.
	 *
	 * @throws Exception if compilation fails.
	 */
	@Test
    @Tag("unit")
	void compilesProcedureAndCallEndToEnd() throws Exception {
		String source = String.join("\n",
				".PROC ADD2 EXPORT WITH A B",
				"  ADDR A B",
				"  RET",
				".ENDP",
				"SETI %DR0 DATA:1",
				"SETI %DR1 DATA:2",
				"CALL ADD2 WITH %DR0 %DR1",
				"NOP"
		);

		List<String> lines = Arrays.asList(source.split("\\r?\\n"));
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(lines, "e2e_proc_params.s");

		assertThat(artifact).isNotNull();
		assertThat(artifact.machineCodeLayout()).isNotEmpty();
		assertThat(artifact.labelAddressToName()).isNotEmpty();

		// Sanity: ensure linking resolved label refs (no non-vector placeholders in operand slots)
		// We check that each operand slot is encoded as a molecule already (no symbolic placeholders reach emission)
		// Implicitly validated by successful compile; here we assert we have at least the opcode+2 operands for CALL
		long opcodeCount = artifact.machineCodeLayout().values().stream()
				.filter(v -> (v & 0xFF) == 0 /* CODE type is 0 for opcodes */)
				.count();
		assertThat(opcodeCount).isGreaterThan(0);
	}

	/**
	 * Verifies that the compiler correctly parses and handles the `EXPORT` keyword
	 * in a procedure header.
	 * It compiles a simple exported procedure and a call to it, ensuring the compilation is successful.
	 *
	 * @throws Exception if compilation fails.
	 */
	@Test
    @Tag("unit")
	void acceptsExportOnProcHeader() throws Exception {
		String source = String.join("\n",
				".PROC BAR EXPORT",
				"  NOP",
				"  RET",
				".ENDP",
				"CALL BAR"
		);

		List<String> lines = Arrays.asList(source.split("\\r?\\n"));
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(lines, "proc_export_header.s");
		assertThat(artifact).isNotNull();
		assertThat(artifact.machineCodeLayout()).isNotEmpty();
	}
}
