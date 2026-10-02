package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ir.DebugInfo;
import org.evochora.compiler.model.ir.IrProgram;

import java.util.List;
import java.util.Map;

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
	 * Uses an empty root alias chain (for single-file compilation) and carries no source files
	 * and no token map.
	 *
	 * @param ast         The semantically validated AST nodes.
	 * @param programName The program name used for IR metadata and diagnostics.
	 * @return The generated IR program.
	 */
	public IrProgram generate(List<AstNode> ast, String programName) {
		return generate(ast, programName, "", List.of(), Map.of());
	}

	/**
	 * Generates a linear IR program by dispatching each AST node to a converter.
	 *
	 * @param ast            The semantically validated AST nodes.
	 * @param programName    The program name used for IR metadata and diagnostics.
	 * @param rootAliasChain The alias chain for the root module (e.g., "MAIN").
	 * @param sources        The text of every file once per placement, with what the preprocessor
	 *                       recorded for it; carried in the program's {@link DebugInfo}.
	 * @param tokenMap       The classification of every token by position; carried in the
	 *                       program's {@link DebugInfo}.
	 * @return The generated IR program.
	 */
	public IrProgram generate(List<AstNode> ast, String programName, String rootAliasChain,
							  List<SourceFile> sources, Map<SourceInfo, TokenInfo> tokenMap) {
		IrGenContext ctx = new IrGenContext(programName, diagnostics, registry, rootAliasChain);
		for (AstNode node : ast) {
			registry.resolve(node).convert(node, ctx);
		}
		return ctx.build(new DebugInfo(sources, tokenMap));
	}
}
