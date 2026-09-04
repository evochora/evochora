package org.evochora.compiler.features.instruction;

import org.evochora.compiler.frontend.semantics.IAnalysisHandler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.IInstructionSet.ArgKind;

import java.util.Optional;

/**
 * Handles the semantic analysis of {@link InstructionNode}s.
 * This involves checking the instruction's arity, argument types, and other constraints.
 */
public class InstructionAnalysisHandler implements IAnalysisHandler {

    private final IInstructionSet isa;

    /**
     * @param isa The instruction set the instructions are checked against.
     */
    public InstructionAnalysisHandler(IInstructionSet isa) {
        this.isa = isa;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(node instanceof InstructionNode instructionNode)) {
            return;
        }

        String instructionName = instructionNode.opcode();
        Optional<Integer> instructionId = isa.getInstructionIdByName(instructionName);

        if (instructionId.isEmpty()) {
            diagnostics.reportError("Unknown instruction '" + instructionName + "'.", instructionNode.sourceInfo().fileName(), instructionNode.sourceInfo().lineNumber());
            return;
        }

        Optional<IInstructionSet.Signature> signatureOpt = isa.getSignatureById(instructionId.get());
        if (signatureOpt.isPresent()) {
            IInstructionSet.Signature signature = signatureOpt.get();
            int expectedArity = signature.getArity();

            int actualArity = instructionNode.arguments().size();

            if (expectedArity != actualArity) {
                diagnostics.reportError(
                        String.format("Instruction '%s' expects %d argument(s), but got %d.",
                                instructionName, expectedArity, actualArity),
                        instructionNode.sourceInfo().fileName(),
                        instructionNode.sourceInfo().lineNumber()
                );
                return;
            }

            for (int i = 0; i < expectedArity; i++) {
                AstNode argumentNode = instructionNode.arguments().get(i);
                ArgKind expectedType = signature.argumentTypes().get(i);

                // An identifier is checked by what it stands for
                if (argumentNode instanceof IdentifierNode idNode) {
                    Optional<ResolvedSymbol> symbolOpt = symbolTable.resolve(idNode.text(), idNode.sourceInfo().fileName());

                    if (symbolOpt.isPresent()) {
                        // What the identifier stands for is asked of the node that defined it:
                        // a binding is checked as the argument it binds to, a jump target may
                        // stand where a label or a jump vector is expected, anything else is
                        // no argument at all.
                        AstNode definition = symbolOpt.get().symbol().node();
                        if (definition instanceof IIdentifierBinding binding) {
                            ArgKind actualType = getArgumentTypeFromNode(binding.bind(idNode));
                            if (expectedType != actualType) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got %s.",
                                                i + 1, instructionName, expectedType, actualType),
                                        instructionNode.sourceInfo().fileName(),
                                        instructionNode.sourceInfo().lineNumber()
                                );
                            }
                        } else if (definition instanceof IJumpTarget) {
                            if (expectedType != ArgKind.LABEL && expectedType != ArgKind.VECTOR) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got LABEL.",
                                                i + 1, instructionName, expectedType),
                                        instructionNode.sourceInfo().fileName(),
                                        instructionNode.sourceInfo().lineNumber()
                                );
                            }
                        } else {
                            diagnostics.reportError(
                                    String.format("'%s' is not a value, a register or a label and cannot be an instruction argument.",
                                            idNode.text()),
                                    instructionNode.sourceInfo().fileName(),
                                    instructionNode.sourceInfo().lineNumber()
                            );
                        }
                    } else {
                        // Allow unresolved if a VECTOR is expected (forward-referenced label to be linked)
                        if (expectedType != ArgKind.VECTOR) {
                            diagnostics.reportError(
                                    String.format("Symbol '%s' is not defined.", idNode.text()),
                                    idNode.sourceInfo().fileName(),
                                    idNode.sourceInfo().lineNumber()
                            );
                        }
                    }
                } else {
                    // Normal type checking for non-identifiers
                    ArgKind actualType = getArgumentTypeFromNode(argumentNode);
                    if (expectedType != actualType) {
                        diagnostics.reportError(
                                String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got %s.",
                                        i + 1, instructionName, expectedType, actualType),
                                instructionNode.sourceInfo().fileName(),
                                instructionNode.sourceInfo().lineNumber()
                        );
                    }

                    // 2) Strict typing: prohibit untyped literals when a type is expected
                    if (isa.requiresTypedLiterals() && expectedType == ArgKind.LITERAL && argumentNode instanceof NumberLiteralNode) {
                        diagnostics.reportError(
                                String.format("Argument %d for instruction '%s' requires a typed literal (e.g., DATA:42).",
                                        i + 1, instructionName),
                                instructionNode.sourceInfo().fileName(),
                                instructionNode.sourceInfo().lineNumber()
                        );
                    }
                }
            }
        }
    }

    private ArgKind getArgumentTypeFromNode(AstNode node) {
        if (node instanceof RegisterNode regNode) {
            return isa.parseRegister(regNode.name()).map(ref -> ref.bank().location()).orElse(false)
                    ? ArgKind.LOCATION_REGISTER : ArgKind.REGISTER;
        }
        if (node instanceof NumberLiteralNode || node instanceof TypedLiteralNode) return ArgKind.LITERAL;
        if (node instanceof VectorLiteralNode) return ArgKind.VECTOR;
        if (node instanceof IdentifierNode) return ArgKind.LABEL;
        throw new IllegalArgumentException("Unsupported argument node type: " + node.getClass().getSimpleName());
    }
}