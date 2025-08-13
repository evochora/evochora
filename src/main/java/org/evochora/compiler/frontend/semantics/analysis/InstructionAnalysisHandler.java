package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;

import java.util.Optional;

public class InstructionAnalysisHandler implements IAnalysisHandler {

    private final SymbolTable symbolTable;
    private final DiagnosticsEngine diagnostics;

    public InstructionAnalysisHandler(SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
    }

    @Override
    public void analyze(AstNode node, SymbolTable ignored, DiagnosticsEngine ignoredDiags) {
        if (!(node instanceof InstructionNode instructionNode)) {
            return;
        }

        String instructionName = instructionNode.opcode().text();
        Integer instructionId = Instruction.getInstructionIdByName(instructionName);

        if (instructionId == null) {
            diagnostics.reportError("Unknown instruction '" + instructionName + "'.", "Unknown", instructionNode.opcode().line());
            return;
        }

        Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(instructionId);
        if (signatureOpt.isPresent()) {
            InstructionSignature signature = signatureOpt.get();
            int expectedArity = signature.getArity();
            int actualArity = instructionNode.arguments().size();

            if (expectedArity != actualArity) {
                diagnostics.reportError(
                        String.format("Instruction '%s' expects %d argument(s), but got %d.",
                                instructionName, expectedArity, actualArity),
                        "Unknown",
                        instructionNode.opcode().line()
                );
                return;
            }

            for (int i = 0; i < expectedArity; i++) {
                AstNode argumentNode = instructionNode.arguments().get(i);
                InstructionArgumentType expectedType = signature.argumentTypes().get(i);

                // Behandle Konstanten-Ersetzung
                if (argumentNode instanceof IdentifierNode idNode) {
                    Optional<Symbol> symbolOpt = symbolTable.resolve(idNode.identifierToken());
                    if (symbolOpt.isPresent()) {
                        Symbol symbol = symbolOpt.get();
                        if (symbol.type() == Symbol.Type.CONSTANT) {
                            // Wenn eine Konstante gefunden wird, behandeln wir sie wie ein Literal
                            if (expectedType != InstructionArgumentType.LITERAL) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got CONSTANT.",
                                                i + 1, instructionName, expectedType),
                                        "Unknown",
                                        instructionNode.opcode().line()
                                );
                            }
                        } else if (symbol.type() == Symbol.Type.LABEL) {
                            if (expectedType != InstructionArgumentType.LABEL) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got LABEL.",
                                                i + 1, instructionName, expectedType),
                                        "Unknown",
                                        instructionNode.opcode().line()
                                );
                            }
                        }
                    } else {
                        diagnostics.reportError(
                                String.format("Symbol '%s' is not defined.", idNode.identifierToken().text()),
                                "Unknown",
                                idNode.identifierToken().line()
                        );
                    }
                } else {
                    // Normale Typprüfung für Nicht-Identifier
                    InstructionArgumentType actualType = getArgumentTypeFromNode(argumentNode);
                    if (expectedType != actualType) {
                        diagnostics.reportError(
                                String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got %s.",
                                        i + 1, instructionName, expectedType, actualType),
                                "Unknown",
                                instructionNode.opcode().line()
                        );
                    }
                }
            }
        }
    }

    private InstructionArgumentType getArgumentTypeFromNode(AstNode node) {
        if (node instanceof RegisterNode) return InstructionArgumentType.REGISTER;
        if (node instanceof NumberLiteralNode || node instanceof TypedLiteralNode) return InstructionArgumentType.LITERAL;
        if (node instanceof VectorLiteralNode) return InstructionArgumentType.VECTOR;
        if (node instanceof IdentifierNode) return InstructionArgumentType.LABEL; // Wird als Label behandelt, bis Symbol aufgelöst ist
        return null;
    }
}