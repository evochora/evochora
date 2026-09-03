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
import java.util.List;

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
	 * Gives converters access to error reporting, so a rejected construct is recorded as a
	 * diagnostic instead of aborting IR generation.
	 *
	 * @return The diagnostics engine.
	 */
	public DiagnosticsEngine diagnostics() {
		return diagnostics;
	}

	/**
	 * Determines the source location to attach to IR emitted for a node.
	 *
	 * @param node The AST node whose origin is wanted.
	 * @return The node's own source info, or a placeholder naming file {@code "unknown"} at
	 *         line and column {@code -1} for node types that carry no location.
	 */
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

	// --- Alias chain stack management ---

	/**
	 * Pushes an alias chain when entering an imported module.
	 * @param aliasChain The chain of the module being entered. Null pushes a copy of the
	 *                   current chain, so the qualification in effect does not change.
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
	 * @return The chain used to qualify names of the module currently being converted. Never
	 *         null; it is the empty string when the context was built without a root chain.
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
	 * Handles registers, literals, vectors and identifiers. Aliases, parameters and constants
	 * were replaced by registers and literals in Phase 6, so an identifier that is still one
	 * here names a label.
	 *
	 * @param node The AST node to convert.
	 * @return The corresponding IR operand.
	 */
	public IrOperand convertOperand(AstNode node) {
		if (node instanceof RegisterNode r) {
			return new IrReg(r.name());
		} else if (node instanceof NumberLiteralNode n) {
			return new IrImm(n.value());
		} else if (node instanceof TypedLiteralNode t) {
			return new IrTypedImm(t.typeName(), t.value());
		} else if (node instanceof VectorLiteralNode v) {
			int[] comps = v.values().stream().mapToInt(Integer::intValue).toArray();
			return new IrVec(comps);
		} else if (node instanceof IdentifierNode id) {
			return new IrLabelRef(id.text());
		}
		throw new IllegalArgumentException("Unsupported operand node type: " + node.getClass().getSimpleName());
	}
}
