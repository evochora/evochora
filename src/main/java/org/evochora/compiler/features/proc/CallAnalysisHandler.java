package org.evochora.compiler.features.proc;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;

import org.evochora.compiler.isa.IInstructionSet;

import java.util.Optional;

/**
 * Semantic analysis handler for CALL instructions.
 * Validates procedure references, REF/VAL/LREF/LVAL argument types and counts.
 */
public class CallAnalysisHandler implements IAnalysisHandler {

    private final IInstructionSet isa;

    /**
     * @param isa The instruction set, which tells a location register from a data register.
     */
    public CallAnalysisHandler(IInstructionSet isa) {
        this.isa = isa;
    }

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
        if (procSymbolOpt.isEmpty() || !(procSymbolOpt.get().symbol().node() instanceof ProcedureNode procedureNode)) {
            diagnostics.reportError("Procedure '" + procIdentifier.text() + "' not found or is not a procedure.",
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
                validateDataIdentifier(idNode, "REF", symbolTable, diagnostics);
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
                validateDataIdentifierOrLabel(idNode, "VAL", symbolTable, diagnostics);
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
                if (isLocationRegister(regNode.name())) continue;
                diagnostics.reportError("LREF arguments must be location registers (LR, PLR, SLR), got '" + regNode.name() + "'.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            } else if (lrefArg instanceof IdentifierNode idNode) {
                validateLocationIdentifier(idNode, "LREF", symbolTable, diagnostics);
            } else {
                diagnostics.reportError("LREF arguments must be location registers.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            }
        }

        // Validate LVAL argument types — location registers or labels
        for (AstNode lvalArg : callNode.lvalArguments()) {
            if (lvalArg instanceof RegisterNode regNode) {
                if (isLocationRegister(regNode.name())) continue;
                diagnostics.reportError("LVAL arguments must be location registers, got '" + regNode.name() + "'.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            } else if (lvalArg instanceof IdentifierNode idNode) {
                validateLocationIdentifierOrLabel(idNode, "LVAL", symbolTable, diagnostics);
            } else {
                diagnostics.reportError("LVAL arguments must be location registers.",
                        callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
            }
        }
    }

    /**
     * What an identifier stands for in a CALL argument, asked of the node that defined it.
     */
    private enum Meaning { DATA_REGISTER, LOCATION_REGISTER, LITERAL, LABEL, NONE, UNRESOLVED }

    private Meaning meaningOf(AstNode definition, IdentifierNode idNode, SymbolTable st) {
        if (!(definition instanceof IIdentifierBinding) && !(definition instanceof IJumpTarget)) {
            return Meaning.NONE;
        }
        // The symbol table follows the definitions; a circle it reports itself
        AstNode bound = st.bindingOf(idNode).orElse(null);
        if (bound == null) return Meaning.UNRESOLVED;
        if (bound instanceof RegisterNode reg) {
            return isLocationRegister(reg.name()) ? Meaning.LOCATION_REGISTER : Meaning.DATA_REGISTER;
        }
        return bound instanceof IdentifierNode ? Meaning.LABEL : Meaning.LITERAL;
    }

    private static String describe(Meaning meaning) {
        return switch (meaning) {
            case DATA_REGISTER -> "a data register";
            case LOCATION_REGISTER -> "a location register";
            case LITERAL -> "a literal";
            case LABEL -> "a label";
            case NONE, UNRESOLVED -> "";
        };
    }

    /**
     * Checks one identifier argument against the meanings its position accepts and reports
     * what it is instead when it does not fit.
     *
     * @param mustExist Whether an identifier the symbol table does not know is reported here;
     *                  a position that accepts a label leaves that to the linker.
     */
    private void validateIdentifier(IdentifierNode idNode, String position, java.util.Set<Meaning> accepted,
                                    String expected, boolean mustExist, SymbolTable st, DiagnosticsEngine diag) {
        Optional<ResolvedSymbol> opt = st.resolve(idNode.text(), idNode.sourceInfo().fileName());
        if (opt.isEmpty()) {
            if (mustExist) {
                diag.reportError(position + " argument '" + idNode.text() + "' is not defined.",
                        idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
            }
            return;
        }
        Meaning meaning = meaningOf(opt.get().symbol().node(), idNode, st);
        if (accepted.contains(meaning) || meaning == Meaning.UNRESOLVED) return;
        if (meaning == Meaning.NONE) {
            diag.reportError("'" + idNode.text() + "' is not a register or a label and cannot be a CALL argument.",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        } else {
            diag.reportError(position + " argument '" + idNode.text() + "' is " + describe(meaning) + ", expected " + expected + ".",
                    idNode.sourceInfo().fileName(), idNode.sourceInfo().lineNumber());
        }
    }

    private void validateDataIdentifier(IdentifierNode idNode, String position,
                                        SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, java.util.EnumSet.of(Meaning.DATA_REGISTER), "a data register", true, st, diag);
    }

    private void validateDataIdentifierOrLabel(IdentifierNode idNode, String position,
                                               SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, java.util.EnumSet.of(Meaning.DATA_REGISTER, Meaning.LITERAL, Meaning.LABEL),
                "a data register", false, st, diag);
    }

    private void validateLocationIdentifier(IdentifierNode idNode, String position,
                                            SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, java.util.EnumSet.of(Meaning.LOCATION_REGISTER), "a location register", true, st, diag);
    }

    private void validateLocationIdentifierOrLabel(IdentifierNode idNode, String position,
                                                   SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, java.util.EnumSet.of(Meaning.LOCATION_REGISTER, Meaning.LITERAL, Meaning.LABEL),
                "a location register", false, st, diag);
    }

    /**
     * Tells whether a register name resolves to a register of a location bank.
     */
    private boolean isLocationRegister(String registerName) {
        return isa.parseRegister(registerName).map(ref -> ref.bank().location()).orElse(false);
    }
}
