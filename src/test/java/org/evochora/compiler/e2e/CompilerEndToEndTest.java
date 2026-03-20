package org.evochora.compiler.e2e;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
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
@Tag("unit")
public class CompilerEndToEndTest {

	private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{100, 100}, true);

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
	void compilesProcedureAndCallEndToEnd() throws Exception {
		String source = String.join("\n",
				"EXPORT .PROC ADD2 REF A B",
				"  ADDR A B",
				"  RET",
				".ENDP",
				"SETI %DR0 DATA:1",
				"SETI %DR1 DATA:2",
				"CALL ADD2 REF %DR0 %DR1",
				"NOP"
		);

		List<String> lines = Arrays.asList(source.split("\\r?\\n"));
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(lines, "e2e_proc_params.s", testEnvProps);

		assertThat(artifact).isNotNull();
		assertThat(artifact.machineCodeLayout()).isNotEmpty();
		assertThat(artifact.labelValueToName()).isNotEmpty();

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
	void acceptsExportOnProcHeader() throws Exception {
		String source = String.join("\n",
				"EXPORT .PROC BAR",
				"  NOP",
				"  RET",
				".ENDP",
				"CALL BAR"
		);

		List<String> lines = Arrays.asList(source.split("\\r?\\n"));
		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(lines, "proc_export_header.s", testEnvProps);
		assertThat(artifact).isNotNull();
		assertThat(artifact.machineCodeLayout()).isNotEmpty();
	}

	/**
	 * Compiles a program exercising multiple features simultaneously:
	 * labels, procedures with REF/VAL, CALL, .DEFINE, .REG, .MACRO.
	 */
	@Test
	void compilesMultiFeatureProgram() throws Exception {
		String source = String.join("\n",
				".DEFINE MAX_VAL DATA:42",
				".REG %TEMP %DR1",
				"",
				".MACRO INC R",
				"  ADDI R DATA:1",
				".ENDM",
				"",
				".ORG 0|0",
				".PROC ADD_TWO REF A B",
				"  ADDS",
				"  RET",
				".ENDP",
				"",
				"START: SETI %DR0 MAX_VAL",
				"  SETR %TEMP %DR0",
				"  INC %TEMP",
				"  CALL ADD_TWO REF %DR0 %TEMP",
				"  JMPI START",
				"");

		Compiler compiler = new Compiler();
		ProgramArtifact artifact = compiler.compile(
				Arrays.asList(source.split("\n")), "multi_feature_test", testEnvProps, null);

		assertThat(artifact.programId()).isNotNull();
		assertThat(artifact.sources()).isNotEmpty();
		assertThat(artifact.sourceMap()).isNotEmpty();
		assertThat(artifact.tokenLookup()).isNotEmpty();
		assertThat(artifact.registerAliasMap()).isNotEmpty();
		assertThat(artifact.labelNameToValue()).isNotEmpty();
		assertThat(artifact.machineCodeLayout()).isNotEmpty();
	}
}
