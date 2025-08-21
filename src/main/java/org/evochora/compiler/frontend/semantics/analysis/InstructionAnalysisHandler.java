package org.evochora.compiler.frontend.semantics.analysis;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.*;
import org.evochora.compiler.frontend.parser.ast.RegisterNode;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.Config;

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
            diagnostics.reportError("Unknown instruction '" + instructionName + "'.", instructionNode.opcode().fileName(), instructionNode.opcode().line());
            return;
        }

        Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(instructionId);
        if (signatureOpt.isPresent()) {
            InstructionSignature signature = signatureOpt.get();
            int expectedArity = signature.getArity();

            // Sonderfall: CALL ... [WITH] ACTUALS
            // Für CALL zählt nur das Ziel (1 Argument). Zusätzliche Operanden
            // nach dem Ziel oder nach WITH werden toleriert als 'actuals'.
            java.util.List<AstNode> argsForSignature = instructionNode.arguments();
            if ("CALL".equalsIgnoreCase(instructionName)) {
                // Nur das Ziel-Label als Signatur-Argument werten
                if (!instructionNode.arguments().isEmpty()) {
                    argsForSignature = instructionNode.arguments().subList(0, 1);
                }
                // Validierung der Actuals: Register oder formale Parameternamen zulassen
                int withIdx = -1;
                for (int i = 0; i < instructionNode.arguments().size(); i++) {
                    AstNode a = instructionNode.arguments().get(i);
                    if (a instanceof IdentifierNode id) {
                        String t = id.identifierToken().text().toUpperCase();
                        if ("WITH".equals(t) || ".WITH".equals(t)) { withIdx = i; break; }
                    }
                }
                // Keine Zusatztoken zwischen Ziel und WITH erlauben (verhindert z.B. "EXPORT" dazwischen)
                int unexpectedEnd = withIdx >= 0 ? withIdx : instructionNode.arguments().size();
                if (unexpectedEnd > 1) {
                    diagnostics.reportError(
                            "CALL syntax error: unexpected token before WITH.",
                            instructionNode.opcode().fileName(),
                            instructionNode.opcode().line()
                    );
                    return;
                }
                int actualsStart = withIdx >= 0 ? withIdx + 1 : 1;
                for (int j = actualsStart; j < instructionNode.arguments().size(); j++) {
                    AstNode arg = instructionNode.arguments().get(j);
                    if (arg instanceof RegisterNode) continue;
                    if (arg instanceof IdentifierNode id) {
                        var res = symbolTable.resolve(id.identifierToken());
                        if (res.isPresent() && res.get().type() == Symbol.Type.VARIABLE) continue;
                    }
                    diagnostics.reportError(
                            "CALL actuals must be registers or parameter names.",
                            instructionNode.opcode().fileName(),
                            instructionNode.opcode().line()
                    );
                    return;
                }
            }

            int actualArity = argsForSignature.size();

            if (expectedArity != actualArity) {
                diagnostics.reportError(
                        String.format("Instruction '%s' expects %d argument(s), but got %d.",
                                instructionName, expectedArity, actualArity),
                        instructionNode.opcode().fileName(),
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
                                        instructionNode.opcode().fileName(),
                                        instructionNode.opcode().line()
                                );
                            }
                        } else if (symbol.type() == Symbol.Type.LABEL || symbol.type() == Symbol.Type.PROCEDURE) {
                            // Labels are valid for LABEL arguments, and also for VECTOR arguments (to be linked to deltas)
                            if (expectedType != InstructionArgumentType.LABEL && expectedType != InstructionArgumentType.VECTOR) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got LABEL.",
                                                i + 1, instructionName, expectedType),
                                        instructionNode.opcode().fileName(),
                                        instructionNode.opcode().line()
                                );
                            }
                        } else if (symbol.type() == Symbol.Type.VARIABLE) {
                            // Formale Parameter als Register-Platzhalter akzeptieren
                            if (expectedType != InstructionArgumentType.REGISTER) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got PARAMETER.",
                                                i + 1, instructionName, expectedType),
                                        instructionNode.opcode().fileName(),
                                        instructionNode.opcode().line()
                                );
                            }
                        }
                    } else {
                        // Allow unresolved if a VECTOR is expected (forward-referenced label to be linked)
                        if (expectedType != InstructionArgumentType.VECTOR) {
                            diagnostics.reportError(
                                    String.format("Symbol '%s' is not defined.", idNode.identifierToken().text()),
                                    idNode.identifierToken().fileName(),
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
                                instructionNode.opcode().fileName(),
                                instructionNode.opcode().line()
                        );
                    }

                    // Zusätzliche Validierungen
                    // 1) Register-Gültigkeit (%DRx, %PRx, %FPRx) – Aliasse werden bereits im Parser ersetzt
                    if (expectedType == InstructionArgumentType.REGISTER && argumentNode instanceof RegisterNode regNode) {
                        String tokenText = regNode.registerToken().text();
                        Optional<Integer> regId = Instruction.resolveRegToken(tokenText);
                        if (regId.isEmpty()) {
                            diagnostics.reportError(
                                    String.format("Unknown register '%s'.", tokenText),
                                    regNode.registerToken().fileName(),
                                    regNode.registerToken().line()
                            );
                        } else {
                            // Verbot: Direkter Zugriff auf %FPRx soll nicht erlaubt sein
                            String u = tokenText.toUpperCase();
                            if (u.startsWith("%FPR")) {
                                diagnostics.reportError(
                                        "Access to formal parameter registers (%FPRx) is not allowed in user code.",
                                        regNode.registerToken().fileName(),
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
                                instructionNode.opcode().fileName(),
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