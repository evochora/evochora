package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;

import org.evochora.compiler.model.ir.IrImm;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrLabelRef;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrProgram;
import org.evochora.compiler.model.ir.IrReg;
import org.evochora.compiler.model.ir.IrTypedImm;
import org.evochora.compiler.model.ir.IrVec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable context passed to converters during IR generation.
 * Provides emission utilities, diagnostics access, SourceInfo construction,
 * and alias-chain-based module qualification.
 */
public final class IrGenContext {

	private final String programName;
	private final DiagnosticsEngine diagnostics;
	private final IrConverterRegistry registry;
	private final List<IrItem> out = new ArrayList<>();
	private final Deque<Map<String, Integer>> procParamScopes = new ArrayDeque<>();
	private final Map<String, org.evochora.compiler.model.ir.IrOperand> constantByNameUpper = new HashMap<>();
	private final Deque<String> aliasChainStack = new ArrayDeque<>();

	/**
	 * Constructs a new IR generation context.
	 * @param programName    The name of the program being compiled.
	 * @param diagnostics    The diagnostics engine for reporting errors and warnings.
	 * @param registry       The registry for resolving AST node converters.
	 * @param rootAliasChain The alias chain for the root module (e.g., "MAIN").
	 */
	public IrGenContext(String programName, DiagnosticsEngine diagnostics, IrConverterRegistry registry,
						String rootAliasChain) {
		this.programName = programName;
		this.diagnostics = diagnostics;
		this.registry = registry;
		aliasChainStack.push(rootAliasChain != null ? rootAliasChain : "");
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
	 * @param params The list of parameter names.
	 */
	public void pushProcedureParams(List<String> params) {
		Map<String, Integer> map = new HashMap<>();
		if (params != null) {
			for (int i = 0; i < params.size(); i++) {
				String name = params.get(i).toUpperCase();
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
	public Optional<Integer> resolveProcedureParam(String identifierUpper) {
		for (Map<String, Integer> scope : procParamScopes) {
			if (scope.containsKey(identifierUpper)) return Optional.of(scope.get(identifierUpper));
		}
		return Optional.empty();
	}

	// --- Alias chain stack management ---

	/**
	 * Pushes an alias chain when entering an imported module.
	 */
	public void pushAliasChain(String aliasChain) {
		aliasChainStack.push(aliasChain != null ? aliasChain : currentAliasChain());
	}

	/**
	 * Pops the alias chain when leaving an imported module.
	 */
	public void popAliasChain() {
		if (aliasChainStack.size() > 1) {
			aliasChainStack.pop();
		}
	}

	/**
	 * Returns the current alias chain.
	 */
	public String currentAliasChain() {
		return aliasChainStack.peek();
	}

	// --- Module-qualified naming ---

	/**
	 * Qualifies a local name with its module prefix derived from the current alias chain.
	 * @param localName The unqualified name (e.g., "HARVEST").
	 * @return The module-qualified name (e.g., "ENERGY.HARVEST").
	 */
	public String qualifyName(String localName) {
		String chain = currentAliasChain();
		if (chain != null && !chain.isEmpty()) {
			return chain + "." + localName.toUpperCase();
		}
		return localName.toUpperCase();
	}

	// --- Operand conversion ---

	/**
	 * Converts an AST operand node into its IR representation.
	 * Handles registers, literals, identifiers (resolving procedure parameters,
	 * constants, and label references), and vectors.
	 *
	 * @param node The AST node to convert.
	 * @return The corresponding IR operand.
	 */
	public IrOperand convertOperand(AstNode node) {
		if (node instanceof RegisterNode r) {
			return new IrReg(r.getName());
		} else if (node instanceof NumberLiteralNode n) {
			return new IrImm(n.value());
		} else if (node instanceof TypedLiteralNode t) {
			return new IrTypedImm(t.typeName(), t.value());
		} else if (node instanceof VectorLiteralNode v) {
			int[] comps = v.values().stream().mapToInt(Integer::intValue).toArray();
			return new IrVec(comps);
		} else if (node instanceof IdentifierNode id) {
			String nameU = id.text().toUpperCase();
			Optional<Integer> idxOpt = resolveProcedureParam(nameU);
			if (idxOpt.isPresent()) {
				return new IrReg("%FDR" + idxOpt.get());
			}
			Optional<IrOperand> constOpt = resolveConstant(nameU);
			if (constOpt.isPresent()) {
				return constOpt.get();
			}
			return new IrLabelRef(id.text());
		}
		throw new IllegalArgumentException("Unsupported operand node type: " + node.getClass().getSimpleName());
	}

	// --- Constant registry for .DEFINE ---

	/**
	 * Registers a named constant with module qualification.
	 * @param nameUpper The upper-case name of the constant.
	 * @param value The operand value.
	 */
	public void registerConstant(String nameUpper, org.evochora.compiler.model.ir.IrOperand value) {
		if (nameUpper != null && value != null) {
			String qualifiedKey = qualifyName(nameUpper);
			constantByNameUpper.put(qualifiedKey, value);
		}
	}

	/**
	 * Resolves a named constant using module-qualified lookup.
	 * @param nameUpper The upper-case name of the constant to resolve.
	 * @return The operand value if found, otherwise empty.
	 */
	public Optional<org.evochora.compiler.model.ir.IrOperand> resolveConstant(String nameUpper) {
		String qualifiedKey = qualifyName(nameUpper);
		return Optional.ofNullable(constantByNameUpper.get(qualifiedKey));
	}
}
