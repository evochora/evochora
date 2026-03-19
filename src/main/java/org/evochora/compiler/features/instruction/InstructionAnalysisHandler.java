package org.evochora.compiler.features.instruction;

import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.model.ast.VectorLiteralNode;
import org.evochora.compiler.frontend.semantics.ResolvedSymbol;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.Config;

import java.util.Optional;

/**
 * Handles the semantic analysis of {@link InstructionNode}s.
 * This involves checking the instruction's arity, argument types, and other constraints.
 */
public class InstructionAnalysisHandler implements IAnalysisHandler {

    /**
     * {@inheritDoc}
     */
    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(node instanceof InstructionNode instructionNode)) {
            return;
        }

        String instructionName = instructionNode.opcode();
        Integer instructionId = Instruction.getInstructionIdByName(instructionName);

        if (instructionId == null) {
            diagnostics.reportError("Unknown instruction '" + instructionName + "'.", instructionNode.sourceInfo().fileName(), instructionNode.sourceInfo().lineNumber());
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
                        instructionNode.sourceInfo().fileName(),
                        instructionNode.sourceInfo().lineNumber()
                );
                return;
            }

            for (int i = 0; i < expectedArity; i++) {
                AstNode argumentNode = instructionNode.arguments().get(i);
                InstructionArgumentType expectedType = signature.argumentTypes().get(i);

                // Handle constant substitution
                if (argumentNode instanceof IdentifierNode idNode) {
                    Optional<ResolvedSymbol> symbolOpt = symbolTable.resolve(idNode.text(), idNode.sourceInfo().fileName());

                    if (symbolOpt.isPresent()) {
                        Symbol symbol = symbolOpt.get().symbol();
                        if (symbol.type() == Symbol.Type.CONSTANT) {
                            if (expectedType != InstructionArgumentType.LITERAL) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got CONSTANT.",
                                                i + 1, instructionName, expectedType),
                                        instructionNode.sourceInfo().fileName(),
                                        instructionNode.sourceInfo().lineNumber()
                                );
                            }
                        } else if (symbol.type() == Symbol.Type.LABEL || symbol.type() == Symbol.Type.PROCEDURE) {
                            // Labels are valid for LABEL arguments, and also for VECTOR arguments (to be linked to deltas)
                            if (expectedType != InstructionArgumentType.LABEL && expectedType != InstructionArgumentType.VECTOR) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got LABEL.",
                                                i + 1, instructionName, expectedType),
                                        instructionNode.sourceInfo().fileName(),
                                        instructionNode.sourceInfo().lineNumber()
                                );
                            }
                        } else if (symbol.type() == Symbol.Type.ALIAS) {
                            // Register aliases are valid for REGISTER and LOCATION_REGISTER arguments (will be resolved later)
                            if (expectedType != InstructionArgumentType.REGISTER && expectedType != InstructionArgumentType.LOCATION_REGISTER) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got ALIAS.",
                                                i + 1, instructionName, expectedType),
                                        instructionNode.sourceInfo().fileName(),
                                        instructionNode.sourceInfo().lineNumber()
                                );
                            }
                        } else if (symbol.type() == Symbol.Type.VARIABLE) {
                            // Accept formal parameters as register placeholders
                            if (expectedType != InstructionArgumentType.REGISTER) {
                                diagnostics.reportError(
                                        String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got PARAMETER.",
                                                i + 1, instructionName, expectedType),
                                        instructionNode.sourceInfo().fileName(),
                                        instructionNode.sourceInfo().lineNumber()
                                );
                            }
                        }
                    } else {
                        // Allow unresolved if a VECTOR is expected (forward-referenced label to be linked)
                        if (expectedType != InstructionArgumentType.VECTOR) {
                            diagnostics.reportError(
                                    String.format("Symbol '%s' is not defined.", idNode.text()),
                                    idNode.sourceInfo().fileName(),
                                    idNode.sourceInfo().lineNumber()
                            );
                        }
                    }
                } else {
                    // Normal type checking for non-identifiers
                    InstructionArgumentType actualType = getArgumentTypeFromNode(argumentNode);
                    if (expectedType != actualType) {
                        diagnostics.reportError(
                                String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got %s.",
                                        i + 1, instructionName, expectedType, actualType),
                                instructionNode.sourceInfo().fileName(),
                                instructionNode.sourceInfo().lineNumber()
                        );
                    }

                    // Additional validations
                    // 1) Register validity (%DRx, %PRx, %FPRx) - aliases are already replaced in the parser
                    if (expectedType == InstructionArgumentType.REGISTER && argumentNode instanceof RegisterNode regNode) {
                        String tokenText = regNode.getName();
                        String u = tokenText.toUpperCase();
                        
                        // Validate register bounds based on configuration
                        if (u.startsWith("%DR")) {
                            try {
                                int regNum = Integer.parseInt(u.substring(3));
                                if (regNum < 0 || regNum >= Config.NUM_DATA_REGISTERS) {
                                    diagnostics.reportError(
                                            String.format("Data register '%s' is out of bounds. Valid range: %%DR0-%%DR%d.", 
                                                tokenText, Config.NUM_DATA_REGISTERS - 1),
                                            regNode.sourceInfo().fileName(),
                                            regNode.sourceInfo().lineNumber()
                                    );
                                    return;
                                }
                            } catch (NumberFormatException e) {
                                diagnostics.reportError(
                                        String.format("Invalid data register format '%s'.", tokenText),
                                        regNode.sourceInfo().fileName(),
                                        regNode.sourceInfo().lineNumber()
                                );
                                return;
                            }
                        } else if (u.startsWith("%PR")) {
                            try {
                                int regNum = Integer.parseInt(u.substring(3));
                                if (regNum < 0 || regNum >= Config.NUM_PROC_REGISTERS) {
                                    diagnostics.reportError(
                                            String.format("Procedure register '%s' is out of bounds. Valid range: %%PR0-%%PR%d.", 
                                                tokenText, Config.NUM_PROC_REGISTERS - 1),
                                            regNode.sourceInfo().fileName(),
                                            regNode.sourceInfo().lineNumber()
                                    );
                                    return;
                                }
                            } catch (NumberFormatException e) {
                                diagnostics.reportError(
                                        String.format("Invalid procedure register format '%s'.", tokenText),
                                        regNode.sourceInfo().fileName(),
                                        regNode.sourceInfo().lineNumber()
                                );
                                return;
                            }
                        } else if (u.startsWith("%FPR")) {
                            try {
                                int regNum = Integer.parseInt(u.substring(4));
                                if (regNum < 0 || regNum >= Config.NUM_FORMAL_PARAM_REGISTERS) {
                                    diagnostics.reportError(
                                            String.format("Formal parameter register '%s' is out of bounds. Valid range: %%FPR0-%%FPR%d.", 
                                                tokenText, Config.NUM_FORMAL_PARAM_REGISTERS - 1),
                                            regNode.sourceInfo().fileName(),
                                            regNode.sourceInfo().lineNumber()
                                    );
                                    return;
                                }
                            } catch (NumberFormatException e) {
                                diagnostics.reportError(
                                        String.format("Invalid formal parameter register format '%s'.", tokenText),
                                        regNode.sourceInfo().fileName(),
                                        regNode.sourceInfo().lineNumber()
                                );
                                return;
                            }
                            
                            // Prohibition: Direct access to %FPRx should not be allowed
                            diagnostics.reportError(
                                    "Access to formal parameter registers (%FPRx) is not allowed in user code.",
                                    regNode.sourceInfo().fileName(),
                                    regNode.sourceInfo().lineNumber()
                            );
                            return;
                        }
                        
                        // If we get here, the register format is valid, so resolve it
                        Optional<Integer> regId = Instruction.resolveRegToken(tokenText);
                        if (regId.isEmpty()) {
                            diagnostics.reportError(
                                    String.format("Unknown register '%s'.", tokenText),
                                    regNode.sourceInfo().fileName(),
                                    regNode.sourceInfo().lineNumber()
                            );
                        }
                    } else if (expectedType == InstructionArgumentType.LOCATION_REGISTER && argumentNode instanceof RegisterNode regNode) {
                        String tokenText = regNode.getName();
                        String u = tokenText.toUpperCase();
                        
                        if (u.startsWith("%LR")) {
                            try {
                                int regNum = Integer.parseInt(u.substring(3));
                                if (regNum < 0 || regNum >= Config.NUM_LOCATION_REGISTERS) {
                                    diagnostics.reportError(
                                            String.format("Location register '%s' is out of bounds. Valid range: %%LR0-%%LR%d.", 
                                                tokenText, Config.NUM_LOCATION_REGISTERS - 1),
                                            regNode.sourceInfo().fileName(),
                                            regNode.sourceInfo().lineNumber()
                                    );
                                    return;
                                }
                            } catch (NumberFormatException e) {
                                diagnostics.reportError(
                                        String.format("Invalid location register format '%s'.", tokenText),
                                        regNode.sourceInfo().fileName(),
                                        regNode.sourceInfo().lineNumber()
                                );
                                return;
                            }
                        } else {
                            diagnostics.reportError(
                                    String.format("Argument %d for instruction '%s' expects a location register (%%LRx), but got '%s'.",
                                        i + 1, instructionName, tokenText),
                                    regNode.sourceInfo().fileName(),
                                    regNode.sourceInfo().lineNumber()
                            );
                            return;
                        }
                        
                        // Resolve register token
                        Optional<Integer> regId = Instruction.resolveRegToken(tokenText);
                        if (regId.isEmpty()) {
                            diagnostics.reportError(
                                    String.format("Unknown location register '%s'.", tokenText),
                                    regNode.sourceInfo().fileName(),
                                    regNode.sourceInfo().lineNumber()
                            );
                        }
                    }

                    // 2) Strict typing: prohibit untyped literals when a type is expected
                    if (Config.STRICT_TYPING && expectedType == InstructionArgumentType.LITERAL && argumentNode instanceof NumberLiteralNode) {
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

    private InstructionArgumentType getArgumentTypeFromNode(AstNode node) {
        if (node instanceof RegisterNode regNode) {
            String tokenText = regNode.getName().toUpperCase();
            if (tokenText.startsWith("%LR")) {
                return InstructionArgumentType.LOCATION_REGISTER;
            }
            return InstructionArgumentType.REGISTER;
        }
        if (node instanceof NumberLiteralNode || node instanceof TypedLiteralNode) return InstructionArgumentType.LITERAL;
        if (node instanceof VectorLiteralNode) return InstructionArgumentType.VECTOR;
        if (node instanceof IdentifierNode) return InstructionArgumentType.LABEL;
        throw new IllegalArgumentException("Unsupported argument node type: " + node.getClass().getSimpleName());
    }
}