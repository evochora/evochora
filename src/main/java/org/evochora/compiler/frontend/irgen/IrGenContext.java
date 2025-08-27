package org.evochora.compiler.frontend.irgen;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.IdentifierNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.NumberLiteralNode;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrProgram;

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
	private final List<IrItem> out = new ArrayList<>();
	private final Deque<Map<String, Integer>> procParamScopes = new ArrayDeque<>();
	private final Map<String, org.evochora.compiler.ir.IrOperand> constantByNameUpper = new HashMap<>();

	/**
	 * Constructs a new IR generation context.
	 * @param programName The name of the program being compiled.
	 * @param diagnostics The diagnostics engine for reporting errors and warnings.
	 * @param registry The registry for resolving AST node converters.
	 */
	public IrGenContext(String programName, DiagnosticsEngine diagnostics, IrConverterRegistry registry) {
		this.programName = programName;
		this.diagnostics = diagnostics;
		this.registry = registry;
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

	// --- START OF CORRECTION ---

	/**
	 * Reconstructs the text of a complete instruction line from the AST node.
	 * @param node The AST node of the instruction.
	 * @return A string representing the complete line.
	 */
	private String reconstructLineFromInstruction(InstructionNode node) {
		StringBuilder sb = new StringBuilder();
		sb.append(node.opcode().text());

		for (AstNode arg : node.arguments()) {
			sb.append(" ");
			if (arg instanceof RegisterNode rn) {
				sb.append(rn.registerToken().text());
			} else if (arg instanceof NumberLiteralNode nn) {
				sb.append(nn.numberToken().text());
			} else if (arg instanceof TypedLiteralNode tn) {
				sb.append(tn.type().text()).append(":").append(tn.value().text());
			} else if (arg instanceof VectorLiteralNode vn) {
				String vec = vn.components().stream().map(Token::text).collect(java.util.stream.Collectors.joining("|"));
				sb.append(vec);
			} else if (arg instanceof IdentifierNode in) {
				sb.append(in.identifierToken().text());
			}
		}
		return sb.toString();
	}

	public SourceInfo sourceOf(AstNode node) {
		if (node instanceof InstructionNode n && n.opcode() != null) {
			// For instructions, we reconstruct the line.
			return new SourceInfo(
					n.opcode().fileName(),
					n.opcode().line(),
					reconstructLineFromInstruction(n)
			);
		}

		// For all other nodes, we continue to use a representative token.
		Token representative = getRepresentativeToken(node);
		if (representative != null) {
			return new SourceInfo(representative.fileName(), representative.line(), representative.text());
		}

		return new SourceInfo("unknown", -1, "");
	}

	private Token getRepresentativeToken(AstNode node) {
		if (node instanceof LabelNode n) return n.labelToken();
		if (node instanceof RegisterNode n) return n.registerToken();
		if (node instanceof NumberLiteralNode n) return n.numberToken();
		if (node instanceof TypedLiteralNode n) return n.type();
		if (node instanceof IdentifierNode n) return n.identifierToken();
		if (node instanceof ProcedureNode n) return n.name();
		if (node instanceof ScopeNode n) return n.name();
		if (node instanceof VectorLiteralNode n && !n.components().isEmpty()) return n.components().get(0);
		return null;
	}

	// --- END OF CORRECTION ---

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

	// --- Constant registry for .DEFINE ---

	/**
	 * Registers a named constant.
	 * @param nameUpper The upper-case name of the constant.
	 * @param value The operand value.
	 */
	public void registerConstant(String nameUpper, org.evochora.compiler.ir.IrOperand value) {
		if (nameUpper != null && value != null) {
			constantByNameUpper.put(nameUpper, value);
		}
	}

	/**
	 * Resolves a named constant.
	 * @param nameUpper The upper-case name of the constant to resolve.
	 * @return The operand value if found, otherwise empty.
	 */
	public java.util.Optional<org.evochora.compiler.ir.IrOperand> resolveConstant(String nameUpper) {
		return java.util.Optional.ofNullable(constantByNameUpper.get(nameUpper));
	}
}