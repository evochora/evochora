package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;

import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrProgram;

import org.evochora.compiler.frontend.semantics.ModuleId;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;

/**
 * Mutable context passed to converters during IR generation.
 * Provides emission utilities, diagnostics access, and SourceInfo construction.
 */
public final class IrGenContext {

	private final String programName;
	private final DiagnosticsEngine diagnostics;
	private final IrConverterRegistry registry;
	private final Map<String, ModuleId> fileToModule;
	private final List<IrItem> out = new ArrayList<>();
	private final Deque<Map<String, Integer>> procParamScopes = new ArrayDeque<>();
	private final Map<String, org.evochora.compiler.model.ir.IrOperand> constantByNameUpper = new HashMap<>();

	/**
	 * Constructs a new IR generation context.
	 * @param programName The name of the program being compiled.
	 * @param diagnostics The diagnostics engine for reporting errors and warnings.
	 * @param registry The registry for resolving AST node converters.
	 * @param fileToModule Mapping from source file paths to their module identifiers.
	 */
	public IrGenContext(String programName, DiagnosticsEngine diagnostics, IrConverterRegistry registry,
						Map<String, ModuleId> fileToModule) {
		this.programName = programName;
		this.diagnostics = diagnostics;
		this.registry = registry;
		this.fileToModule = fileToModule != null ? fileToModule : Map.of();
	}

	/**
	 * Emits a new IR item.
	 * @param item The item to add to the program.
	 */
	public void emit(IrItem item) {
		out.add(item);
	}

	/**
	 * Converts the given AST node by resolving and invoking the appropriate converter.
	 * @param node The node to convert.
	 */
	public void convert(AstNode node) {
		registry.resolve(node).convert(node, this);
	}

	/**
	 * @return The diagnostics engine.
	 */
	public DiagnosticsEngine diagnostics() {
		return diagnostics;
	}

	public SourceInfo sourceOf(AstNode node) {
		if (node instanceof ISourceLocatable locatable) {
			return locatable.sourceInfo();
		}
		return new SourceInfo("unknown", -1, -1);
	}

	/**
	 * Builds the final {@link IrProgram} from the emitted items.
	 * @return The constructed program.
	 */
	public IrProgram build() {
		return new IrProgram(programName, List.copyOf(out));
	}

	// --- Procedure parameter scope management ---

	/**
	 * Pushes a new parameter scope for a procedure.
	 * @param params The list of parameter name tokens.
	 */
	public void pushProcedureParams(List<Token> params) {
		Map<String, Integer> map = new HashMap<>();
		if (params != null) {
			for (int i = 0; i < params.size(); i++) {
				String name = params.get(i).text().toUpperCase();
				map.put(name, i);
			}
		}
		procParamScopes.push(map);
	}

	/**
	 * Pops the current procedure parameter scope.
	 */
	public void popProcedureParams() {
		if (!procParamScopes.isEmpty()) procParamScopes.pop();
	}

	/**
	 * Resolves a procedure parameter by name, searching scopes from newest to oldest.
	 * @param identifierUpper The upper-case identifier to resolve.
	 * @return The parameter index if found, otherwise empty.
	 */
	public java.util.Optional<Integer> resolveProcedureParam(String identifierUpper) {
		for (Map<String, Integer> scope : procParamScopes) {
			if (scope.containsKey(identifierUpper)) return java.util.Optional.of(scope.get(identifierUpper));
		}
		return java.util.Optional.empty();
	}

	// --- Module-qualified naming ---

	/**
	 * Qualifies a local name with its module prefix derived from the source file.
	 * @param localName The unqualified name (e.g., "HARVEST").
	 * @param fileName The source file path where the name is defined.
	 * @return The module-qualified name (e.g., "ENERGY.HARVEST").
	 */
	public String qualifyName(String localName, String fileName) {
		ModuleId moduleId = fileToModule.get(fileName);
		String moduleName = moduleId != null
			? ModuleId.deriveModuleName(moduleId.path())
			: ModuleId.deriveModuleName(fileName);
		return moduleName + "." + localName.toUpperCase();
	}

	// --- Constant registry for .DEFINE ---

	/**
	 * Registers a named constant with module qualification.
	 * @param nameUpper The upper-case name of the constant.
	 * @param value The operand value.
	 * @param fileName The source file where the constant is defined.
	 */
	public void registerConstant(String nameUpper, org.evochora.compiler.model.ir.IrOperand value, String fileName) {
		if (nameUpper != null && value != null) {
			String qualifiedKey = qualifyName(nameUpper, fileName);
			constantByNameUpper.put(qualifiedKey, value);
		}
	}

	/**
	 * Resolves a named constant using module-qualified lookup.
	 * @param nameUpper The upper-case name of the constant to resolve.
	 * @param fileName The source file requesting the constant (for module context).
	 * @return The operand value if found, otherwise empty.
	 */
	public java.util.Optional<org.evochora.compiler.model.ir.IrOperand> resolveConstant(String nameUpper, String fileName) {
		String qualifiedKey = qualifyName(nameUpper, fileName);
		return java.util.Optional.ofNullable(constantByNameUpper.get(qualifiedKey));
	}
}