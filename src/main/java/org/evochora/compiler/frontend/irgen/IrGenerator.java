package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ir.IrProgram;

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
	 * Uses an empty root alias chain (for single-file compilation).
	 *
	 * @param ast         The semantically validated AST nodes.
	 * @param programName The program name used for IR metadata and diagnostics.
	 * @return The generated IR program.
	 */
	public IrProgram generate(List<AstNode> ast, String programName) {
		return generate(ast, programName, "");
	}

	/**
	 * Generates a linear IR program by dispatching each AST node to a converter.
	 *
	 * @param ast            The semantically validated AST nodes.
	 * @param programName    The program name used for IR metadata and diagnostics.
	 * @param rootAliasChain The alias chain for the root module (e.g., "MAIN").
	 * @return The generated IR program.
	 */
	public IrProgram generate(List<AstNode> ast, String programName, String rootAliasChain) {
		IrGenContext ctx = new IrGenContext(programName, diagnostics, registry, rootAliasChain);
		for (AstNode node : ast) {
			registry.resolve(node).convert(node, ctx);
		}
		return ctx.build();
	}
}
