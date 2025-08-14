package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.app.setup.Config;

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

            // Sonderfall: CALL ... WITH %R1 %R2 ...
            // Alles nach WITH (oder .WITH) wird nicht als Instruktions-Argument gezählt
            java.util.List<AstNode> argsForSignature = instructionNode.arguments();
            if ("CALL".equalsIgnoreCase(instructionName)) {
                int withIdx = -1;
                for (int i = 0; i < instructionNode.arguments().size(); i++) {
                    AstNode a = instructionNode.arguments().get(i);
                    if (a instanceof IdentifierNode id) {
                        String t = id.identifierToken().text().toUpperCase();
                        if ("WITH".equals(t) || ".WITH".equals(t)) { withIdx = i; break; }
                    }
                }
                if (withIdx >= 0) {
                    argsForSignature = instructionNode.arguments().subList(0, withIdx);
                    // Validierung: alles nach WITH müssen Register sein
                    for (int j = withIdx + 1; j < instructionNode.arguments().size(); j++) {
                        if (!(instructionNode.arguments().get(j) instanceof RegisterNode)) {
                            diagnostics.reportError(
                                    "CALL .WITH expects register operands after WITH.",
                                    "Unknown",
                                    instructionNode.opcode().line()
                            );
                            return;
                        }
                    }
                }
            }

            int actualArity = argsForSignature.size();

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
                AstNode argumentNode = argsForSignature.get(i);
                InstructionArgumentType expectedType = signature.argumentTypes().get(i);

                // Behandle Konstanten-Ersetzung
                if (argumentNode instanceof IdentifierNode idNode) {
                    Optional<Symbol> symbolOpt = symbolTable.resolve(idNode.identifierToken());
                    if (symbolOpt.isPresent()) {
                        Symbol symbol = symbolOpt.get();
                        if (symbol.type() == Symbol.Type.CONSTANT) {
                            if (expectedType != InstructionArgumentType.LITERAL) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got CONSTANT.",
                                                i + 1, instructionName, expectedType),
                                        "Unknown",
                                        instructionNode.opcode().line()
                                );
                            }
                        } else if (symbol.type() == Symbol.Type.LABEL) {
                            // Labels are valid for LABEL arguments, and also for VECTOR arguments (to be linked to deltas)
                            if (expectedType != InstructionArgumentType.LABEL && expectedType != InstructionArgumentType.VECTOR) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got LABEL.",
                                                i + 1, instructionName, expectedType),
                                        "Unknown",
                                        instructionNode.opcode().line()
                                );
                            }
                        }
                    } else {
                        // Allow unresolved if a VECTOR is expected (forward-referenced label to be linked)
                        if (expectedType != InstructionArgumentType.VECTOR) {
                            diagnostics.reportError(
                                    String.format("Symbol '%s' is not defined.", idNode.identifierToken().text()),
                                    "Unknown",
                                    idNode.identifierToken().line()
                            );
                        }
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

                    // Zusätzliche Validierungen
                    // 1) Register-Gültigkeit (%DRx, %PRx, %FPRx) – Aliasse werden bereits im Parser ersetzt
                    if (expectedType == InstructionArgumentType.REGISTER && argumentNode instanceof RegisterNode regNode) {
                        String tokenText = regNode.registerToken().text();
                        Integer regId = Instruction.resolveRegToken(tokenText, java.util.Collections.emptyMap());
                        if (regId == null) {
                            diagnostics.reportError(
                                    String.format("Unknown register '%s'.", tokenText),
                                    "Unknown",
                                    regNode.registerToken().line()
                            );
                        } else {
                            // Verbot: Direkter Zugriff auf %FPRx soll nicht erlaubt sein
                            String u = tokenText.toUpperCase();
                            if (u.startsWith("%FPR")) {
                                diagnostics.reportError(
                                        "Access to formal parameter registers (%FPRx) is not allowed in user code.",
                                        "Unknown",
                                        regNode.registerToken().line()
                                );
                            }
                        }
                    }

                    // 2) Strict typing: ungetypte Literale verbieten, wenn Typ erwartet wird
                    if (Config.STRICT_TYPING && expectedType == InstructionArgumentType.LITERAL && argumentNode instanceof NumberLiteralNode) {
                        diagnostics.reportError(
                                String.format("Argument %d for instruction '%s' requires a typed literal (e.g., DATA:42).",
                                        i + 1, instructionName),
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