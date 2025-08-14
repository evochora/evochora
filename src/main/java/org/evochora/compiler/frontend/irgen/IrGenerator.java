package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.ir.IrProgram;

import java.util.List;

/**
 * Phase: Generates IR from a validated AST by delegating to converters
 * resolved via the {@link IrConverterRegistry}.
 */
public final class IrGenerator {

	private final DiagnosticsEngine diagnostics;
	private final IrConverterRegistry registry;

	/**
	 * Creates a new IR generator with a diagnostics engine and a prepared registry.
	 *
	 * @param diagnostics The diagnostics engine for reporting issues.
	 * @param registry    The converter registry.
	 */
	public IrGenerator(DiagnosticsEngine diagnostics, IrConverterRegistry registry) {
		this.diagnostics = diagnostics;
		this.registry = registry;
	}

	/**
	 * Generates a linear IR program by dispatching each AST node to a converter.
	 *
	 * @param ast         The semantically validated AST nodes.
	 * @param programName The program name used for IR metadata and diagnostics.
	 * @return The generated IR program.
	 */
	public IrProgram generate(List<AstNode> ast, String programName) {
		IrGenContext ctx = new IrGenContext(programName, diagnostics, registry);
		for (AstNode node : ast) {
			registry.resolve(node).convert(node, ctx);
		}
		return ctx.build();
	}
}


