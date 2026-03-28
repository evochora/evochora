package org.evochora.compiler.features.proc;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;

import java.util.Optional;

/**
 * Semantic analysis handler for CALL instructions.
 * Validates procedure references, REF/VAL/LREF/LVAL argument types and counts.
 */
public class CallAnalysisHandler implements IAnalysisHandler {

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(node instanceof CallNode callNode)) return;

        // Only validate procedure reference and parameter types when parameters are present
        if (!callNode.refArguments().isEmpty() || !callNode.valArguments().isEmpty()
                || !callNode.lrefArguments().isEmpty() || !callNode.lvalArguments().isEmpty()) {
            analyzeNewSyntax(callNode, symbolTable, diagnostics);
        }
    }

    private void analyzeNewSyntax(CallNode callNode, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(callNode.procedureName() instanceof IdentifierNode procIdentifier)) {
            diagnostics.reportError("CALL with REF/VAL requires a procedure name.",
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            return;
        }

        Optional<ResolvedSymbol> procSymbolOpt = symbolTable.resolve(procIdentifier.text(), procIdentifier.sourceInfo().fileName());
        if (procSymbolOpt.isEmpty() || procSymbolOpt.get().symbol().type() != Symbol.Type.PROCEDURE) {
            diagnostics.reportError("Procedure '" + procIdentifier.text() + "' not found or is not a procedure.",
                    procIdentifier.sourceInfo().fileName(), procIdentifier.sourceInfo().lineNumber());
            return;
        }

        Symbol procSymbol = procSymbolOpt.get().symbol();
        if (!(procSymbol.node() instanceof ProcedureNode procedureNode)) {
            diagnostics.reportError("Internal error: Symbol for procedure '" + procIdentifier.text() + "' does not contain a valid ProcedureNode.",
                    procIdentifier.sourceInfo().fileName(), procIdentifier.sourceInfo().lineNumber());
            return;
        }

        // Validate argument counts
        if (callNode.refArguments().size() != procedureNode.refParameters().size()) {
            diagnostics.reportError(String.format("Procedure '%s' expects %d REF argument(s), but received %d.",
                    procedureNode.name(), procedureNode.refParameters().size(), callNode.refArguments().size()),
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }
        if (callNode.valArguments().size() != procedureNode.valParameters().size()) {
            diagnostics.reportError(String.format("Procedure '%s' expects %d VAL argument(s), but received %d.",
                    procedureNode.name(), procedureNode.valParameters().size(), callNode.valArguments().size()),
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }

        // Validate REF argument types
        for (AstNode refArg : callNode.refArguments()) {
            if (refArg instanceof RegisterNode) continue;
            if (refArg instanceof IdentifierNode idNode) {
                validateDataIdentifier(idNode, "REF", symbolTable, diagnostics, callNode);
                continue;
            }
            diagnostics.reportError("REF arguments must be registers.",
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }

        // Validate VAL argument types
        for (AstNode valArg : callNode.valArguments()) {
            if (valArg instanceof RegisterNode) continue;
            if (valArg instanceof NumberLiteralNode) continue;
            if (valArg instanceof TypedLiteralNode) continue;
            if (valArg instanceof IdentifierNode idNode) {
                validateDataIdentifierOrLabel(idNode, "VAL", symbolTable, diagnostics, callNode);
                continue;
            }
            diagnostics.reportError("VAL arguments must be registers, literals, or labels.",
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }

        // Validate LREF argument counts
        if (callNode.lrefArguments().size() != procedureNode.lrefParameters().size()) {
            diagnostics.reportError(String.format("Procedure '%s' expects %d LREF argument(s), but received %d.",
                    procedureNode.name(), procedureNode.lrefParameters().size(), callNode.lrefArguments().size()),
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }
        if (callNode.lvalArguments().size() != procedureNode.lvalParameters().size()) {
            diagnostics.reportError(String.format("Procedure '%s' expects %d LVAL argument(s), but received %d.",
                    procedureNode.name(), procedureNode.lvalParameters().size(), callNode.lvalArguments().size()),
                    callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
        }

        // Validate LREF argument types — must be location registers
        for (AstNode lrefArg : callNode.lrefArguments()) {
            if (lrefArg instanceof RegisterNode regNode) {
                Optional<Integer> regId = Instruction.resolveRegToken(regNode.getName());
                if (regId.isPresent()) {
                    RegisterBank bank = RegisterBank.forId(regId.get());
                    if (bank != null && bank.isLocation) continue;
                }
                diagnostics.reportError("LREF arguments must be location registers (LR, PLR, SLR), got '" + regNode.getName() + "'.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            } else if (lrefArg instanceof IdentifierNode idNode) {
                validateLocationIdentifier(idNode, "LREF", symbolTable, diagnostics, callNode);
            } else {
                diagnostics.reportError("LREF arguments must be location registers.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            }
        }

        // Validate LVAL argument types — location registers or labels
        for (AstNode lvalArg : callNode.lvalArguments()) {
            if (lvalArg instanceof RegisterNode regNode) {
                Optional<Integer> regId = Instruction.resolveRegToken(regNode.getName());
                if (regId.isPresent()) {
                    RegisterBank bank = RegisterBank.forId(regId.get());
                    if (bank != null && bank.isLocation) continue;
                }
                diagnostics.reportError("LVAL arguments must be location registers, got '" + regNode.getName() + "'.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            } else if (lvalArg instanceof IdentifierNode idNode) {
                validateLocationIdentifierOrLabel(idNode, "LVAL", symbolTable, diagnostics, callNode);
            } else {
                diagnostics.reportError("LVAL arguments must be location registers.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            }
        }
    }

    private void validateDataIdentifier(IdentifierNode idNode, String position,
                                        SymbolTable st, DiagnosticsEngine diag, CallNode call) {
        Optional<ResolvedSymbol> opt = st.resolve(idNode.text(), idNode.sourceInfo().fileName());
        if (opt.isEmpty()) {
            diag.reportError(position + " argument '" + idNode.text() + "' is not defined.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
            return;
        }
        Symbol.Type type = opt.get().symbol().type();
        if (type == Symbol.Type.REGISTER_ALIAS_DATA || type == Symbol.Type.PARAMETER_DATA) return;
        if (type == Symbol.Type.REGISTER_ALIAS_LOCATION) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a location register alias, expected a data register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.PARAMETER_LOCATION) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a location parameter, expected a data register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.MODULE_ALIAS) {
            diag.reportError("Module alias '" + idNode.text() + "' cannot be used as a CALL argument.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else {
            diag.reportError(position + " argument '" + idNode.text()
                    + "' must resolve to a data register alias or data parameter.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        }
    }

    private void validateDataIdentifierOrLabel(IdentifierNode idNode, String position,
                                               SymbolTable st, DiagnosticsEngine diag, CallNode call) {
        Optional<ResolvedSymbol> opt = st.resolve(idNode.text(), idNode.sourceInfo().fileName());
        if (opt.isEmpty()) return;
        Symbol.Type type = opt.get().symbol().type();
        if (type == Symbol.Type.REGISTER_ALIAS_DATA || type == Symbol.Type.PARAMETER_DATA) return;
        if (type == Symbol.Type.LABEL || type == Symbol.Type.PROCEDURE || type == Symbol.Type.CONSTANT) return;
        if (type == Symbol.Type.REGISTER_ALIAS_LOCATION) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a location register alias, expected a data register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.PARAMETER_LOCATION) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a location parameter, expected a data register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.MODULE_ALIAS) {
            diag.reportError("Module alias '" + idNode.text() + "' cannot be used as a CALL argument.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else {
            diag.reportError(position + " argument '" + idNode.text()
                    + "' must resolve to a data register, label, or constant.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        }
    }

    private void validateLocationIdentifier(IdentifierNode idNode, String position,
                                            SymbolTable st, DiagnosticsEngine diag, CallNode call) {
        Optional<ResolvedSymbol> opt = st.resolve(idNode.text(), idNode.sourceInfo().fileName());
        if (opt.isEmpty()) {
            diag.reportError(position + " argument '" + idNode.text() + "' is not defined.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
            return;
        }
        Symbol.Type type = opt.get().symbol().type();
        if (type == Symbol.Type.REGISTER_ALIAS_LOCATION || type == Symbol.Type.PARAMETER_LOCATION) return;
        if (type == Symbol.Type.REGISTER_ALIAS_DATA) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a data register alias, expected a location register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.PARAMETER_DATA) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a data parameter, expected a location register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.MODULE_ALIAS) {
            diag.reportError("Module alias '" + idNode.text() + "' cannot be used as a CALL argument.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else {
            diag.reportError(position + " argument '" + idNode.text()
                    + "' must resolve to a location register alias or location parameter.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        }
    }

    private void validateLocationIdentifierOrLabel(IdentifierNode idNode, String position,
                                                   SymbolTable st, DiagnosticsEngine diag, CallNode call) {
        Optional<ResolvedSymbol> opt = st.resolve(idNode.text(), idNode.sourceInfo().fileName());
        if (opt.isEmpty()) return;
        Symbol.Type type = opt.get().symbol().type();
        if (type == Symbol.Type.REGISTER_ALIAS_LOCATION || type == Symbol.Type.PARAMETER_LOCATION) return;
        if (type == Symbol.Type.LABEL || type == Symbol.Type.PROCEDURE || type == Symbol.Type.CONSTANT) return;
        if (type == Symbol.Type.REGISTER_ALIAS_DATA) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a data register alias, expected a location register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.PARAMETER_DATA) {
            diag.reportError(position + " argument '" + idNode.text() + "' is a data parameter, expected a location register.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else if (type == Symbol.Type.MODULE_ALIAS) {
            diag.reportError("Module alias '" + idNode.text() + "' cannot be used as a CALL argument.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else {
            diag.reportError(position + " argument '" + idNode.text()
                    + "' must resolve to a location register, label, or constant.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        }
    }
}
