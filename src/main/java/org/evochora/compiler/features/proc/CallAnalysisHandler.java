package org.evochora.compiler.features.proc;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.Evaluator;
import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.SymbolTableResolution;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.OperandText;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.RegisterNode;

import org.evochora.compiler.isa.IInstructionSet;

import java.util.EnumSet;
import java.util.Set;

/**
 * Semantic analysis handler for CALL instructions.
 * Validates procedure references, REF/VAL/LREF/LVAL argument types and counts.
 * <p>
 * The target of a CALL is a name; a target of any other kind, written or computed, is of the
 * wrong type. A computation is evaluated here and its outcome recorded in the symbol table: as
 * a target, to report its kind, and as a VAL argument, where its value is a number, a typed
 * value or a vector. A REF, LREF or LVAL argument takes no computation. A position, a molecule
 * type and a value computed from a position, which only the layout knows, stand nowhere,
 * written or through a name.
 */
public class CallAnalysisHandler implements IAnalysisHandler {

    private final IInstructionSet isa;
    private final Evaluator evaluator;

    /**
     * Creates the handler for an instruction set.
     *
     * @param isa        The instruction set, which tells a location register from a data register.
     * @param operations The operations of computations, read when the phase runs.
     */
    public CallAnalysisHandler(IInstructionSet isa, OperationRegistry operations) {
        this.isa = isa;
        this.evaluator = new Evaluator(operations);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        if (!(node instanceof CallNode callNode)) return;

        boolean hasArguments = !callNode.refArguments().isEmpty() || !callNode.valArguments().isEmpty()
                || !callNode.lrefArguments().isEmpty() || !callNode.lvalArguments().isEmpty();
        // The target is a name; anything else, written or computed, is of the wrong type
        AstNode targetNode = callNode.procedureName();
        if (targetNode instanceof ExpressionNode target) {
            evaluate(target, Role.TARGET, symbolTable, diagnostics);
            return;
        }
        if (!(targetNode instanceof IdentifierNode procIdentifier)) {
            reportWrongTarget(callNode, kindOf(targetNode), diagnostics);
            return;
        }

        // The target has to exist for every CALL; only a CALL that passes arguments needs it to
        // be a procedure, a plain CALL may target any label.
        ResolvedSymbol target;
        switch (symbolTable.resolve(procIdentifier.text(), procIdentifier.sourceInfo())) {
            case Resolution.Missing missing -> {
                diagnostics.reportError("Cannot call '" + procIdentifier.text() + "': " + missing.explanation(),
                        procIdentifier.sourceInfo().fileName(), procIdentifier.sourceInfo().lineNumber());
                return;
            }
            case ResolvedSymbol found -> target = found;
        }
        if (!hasArguments) {
            return;
        }
        if (!(target.symbol().node() instanceof ProcedureNode procedureNode)) {
            diagnostics.reportError("Cannot call '" + procIdentifier.text() + "': it is not a procedure.",
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
            report(invalid(refArg, "REF", "is " + describe(refArg), "a register"), callNode.sourceInfo(), diagnostics);
        }

        // Validate VAL argument types: a register, a typed literal or a vector, written or through a constant
        for (AstNode valArg : callNode.valArguments()) {
            if (valArg instanceof RegisterNode) continue;
            if (valArg instanceof LiteralNode literal) {
                if (literal.value() instanceof Value.Number) {
                    report(invalid(literal, "VAL", "is a number without a type", EXPECTED_BY_VAL),
                            literal.sourceInfo(), diagnostics);
                }
                continue;
            }
            if (valArg instanceof ExpressionNode expression) {
                evaluate(expression, Role.VAL, symbolTable, diagnostics);
                continue;
            }
            if (valArg instanceof IdentifierNode idNode) {
                validateDataIdentifierOrLiteral(idNode, "VAL", symbolTable, diagnostics);
                continue;
            }
            report(invalid(valArg, "VAL", "is " + describe(valArg), EXPECTED_BY_VAL), callNode.sourceInfo(), diagnostics);
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
                report(invalid(regNode, "LREF", "is not a location register", A_LOCATION_REGISTER),
                        callNode.sourceInfo(), diagnostics);
            } else if (lrefArg instanceof IdentifierNode idNode) {
                validateLocationIdentifier(idNode, "LREF", symbolTable, diagnostics);
            } else {
                report(invalid(lrefArg, "LREF", "is " + describe(lrefArg), A_LOCATION_REGISTER),
                        callNode.sourceInfo(), diagnostics);
            }
        }

        // Validate LVAL argument types — location registers or labels
        for (AstNode lvalArg : callNode.lvalArguments()) {
            if (lvalArg instanceof RegisterNode regNode) {
                if (isLocationRegister(regNode.name())) continue;
                report(invalid(regNode, "LVAL", "is not a location register", EXPECTED_BY_LVAL),
                        callNode.sourceInfo(), diagnostics);
            } else if (lvalArg instanceof IdentifierNode idNode) {
                validateLocationIdentifierOrLabel(idNode, "LVAL", symbolTable, diagnostics);
            } else {
                report(invalid(lvalArg, "LVAL", "is " + describe(lvalArg), EXPECTED_BY_LVAL),
                        callNode.sourceInfo(), diagnostics);
            }
        }
    }

    /**
     * What an identifier stands for in a CALL argument, asked of the node that defined it.
     */
    private enum Meaning { DATA_REGISTER, LOCATION_REGISTER, LITERAL, NUMBER, TYPE, LABEL, LAYOUT_VALUE, NONE, UNRESOLVED }

    private static final String A_DATA_REGISTER = "a data register";
    private static final String A_LOCATION_REGISTER = "a location register";
    private static final String EXPECTED_BY_VAL = "a register, a typed literal or a vector";
    private static final String EXPECTED_BY_LVAL = "a location register or a label";

    /**
     * What an identifier stands for, asked of the node that defined it and followed through the
     * definitions. A literal here is a typed value or a vector; a number without a type and a
     * molecule type are told apart, since neither can be passed.
     */
    private Meaning meaningOf(AstNode definition, IdentifierNode idNode, SymbolTable st) {
        if (!(definition instanceof IIdentifierBinding) && !(definition instanceof IJumpTarget)) {
            return Meaning.NONE;
        }
        // The symbol table follows the definitions; a circle it reports itself
        SymbolTable.ChainEnd end = st.chainEndOf(idNode).orElse(null);
        if (end == null) return Meaning.UNRESOLVED;
        if (end.symbol() != null && st.definitionValueOf(end.node()).map(outcome -> outcome.remaining() != null).orElse(false)) {
            return Meaning.LAYOUT_VALUE;
        }
        AstNode bound = end.symbol() != null ? new IdentifierNode(end.qualifiedName(), end.sourceInfo()) : end.node();
        if (bound instanceof RegisterNode reg) {
            return isLocationRegister(reg.name()) ? Meaning.LOCATION_REGISTER : Meaning.DATA_REGISTER;
        }
        if (bound instanceof LiteralNode literal) {
            return switch (literal.value()) {
                case Value.Number number -> Meaning.NUMBER;
                case Value.Type type -> Meaning.TYPE;
                default -> Meaning.LITERAL;
            };
        }
        return bound instanceof IdentifierNode ? Meaning.LABEL : Meaning.LITERAL;
    }

    /**
     * Names an argument that is no register and no identifier, for a message.
     */
    private static String describe(AstNode argument) {
        if (argument instanceof LiteralNode literal) {
            return literal.value() instanceof Value.Vector ? "a vector" : "a literal";
        }
        if (argument instanceof ExpressionNode) {
            return "a computation";
        }
        return "of another kind";
    }

    private static String describe(Meaning meaning) {
        return switch (meaning) {
            case DATA_REGISTER -> A_DATA_REGISTER;
            case LOCATION_REGISTER -> A_LOCATION_REGISTER;
            case LITERAL -> "a literal";
            case NUMBER -> "a number without a type";
            case TYPE -> "a molecule type";
            case LABEL -> "a label";
            case LAYOUT_VALUE, NONE, UNRESOLVED -> "";
        };
    }

    /**
     * Checks one identifier argument against the meanings its position accepts and reports
     * what it is instead when it does not fit. An identifier the symbol table does not know is
     * reported here, whatever the position accepts.
     */
    private void validateIdentifier(IdentifierNode idNode, String position, Set<Meaning> accepted,
                                    String expected, SymbolTable st, DiagnosticsEngine diag) {
        ResolvedSymbol resolved;
        switch (st.resolve(idNode.text(), idNode.sourceInfo())) {
            case Resolution.Missing missing -> {
                report("Invalid argument: '" + idNode.text() + "' for " + position + ": " + missing.explanation(),
                        idNode.sourceInfo(), diag);
                return;
            }
            case ResolvedSymbol found -> resolved = found;
        }
        Meaning meaning = meaningOf(resolved.symbol().node(), idNode, st);
        if (accepted.contains(meaning) || meaning == Meaning.UNRESOLVED) return;
        if (meaning == Meaning.LAYOUT_VALUE) {
            boolean isPosition = st.chainEndOf(idNode)
                    .flatMap(end -> st.definitionValueOf(end.node()))
                    .map(outcome -> outcome.kind() == Kind.POSITION).orElse(false);
            report(invalid(idNode, position, isPosition ? "is a position" : "is computed from a position", expected),
                    idNode.sourceInfo(), diag);
        } else if (meaning == Meaning.NONE) {
            report(invalid(idNode, position, "is not a register or a label", expected), idNode.sourceInfo(), diag);
        } else {
            report(invalid(idNode, position, "is " + describe(meaning), expected), idNode.sourceInfo(), diag);
        }
    }

    private void validateDataIdentifier(IdentifierNode idNode, String position,
                                        SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, EnumSet.of(Meaning.DATA_REGISTER), A_DATA_REGISTER, st, diag);
    }

    private void validateDataIdentifierOrLiteral(IdentifierNode idNode, String position,
                                                 SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, EnumSet.of(Meaning.DATA_REGISTER, Meaning.LITERAL),
                EXPECTED_BY_VAL, st, diag);
    }

    private void validateLocationIdentifier(IdentifierNode idNode, String position,
                                            SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, EnumSet.of(Meaning.LOCATION_REGISTER), A_LOCATION_REGISTER, st, diag);
    }

    private void validateLocationIdentifierOrLabel(IdentifierNode idNode, String position,
                                                   SymbolTable st, DiagnosticsEngine diag) {
        validateIdentifier(idNode, position, EnumSet.of(Meaning.LOCATION_REGISTER, Meaning.LABEL),
                EXPECTED_BY_LVAL, st, diag);
    }

    /**
     * Where a computation stands in a CALL: as its target, or as a VAL argument.
     */
    private enum Role { TARGET, VAL }

    /**
     * Evaluates a computation that stands for a literal, records its outcome, and reports what
     * keeps it from being one: a molecule type, a position, a value computed from a position,
     * which only the layout knows, or the error of the computation. The target is reported as
     * the first argument of the CALL instruction, a VAL argument as what VAL takes.
     *
     * @return The result of the evaluation.
     */
    private EvaluationResult evaluate(ExpressionNode expression, Role role, SymbolTable symbolTable,
                                      DiagnosticsEngine diagnostics) {
        EvaluationResult result = evaluator.evaluate(expression.expression(),
                new SymbolTableResolution(symbolTable), IEvaluationContext.none());
        switch (result) {
            case EvaluationResult.Value value -> {
                symbolTable.recordResult(expression, ExpressionOutcome.of(value.value()));
                if (value.value() instanceof Value.Type) {
                    reportUnusable(expression, role, "TYPE", diagnostics);
                } else if (value.value() instanceof Value.Position) {
                    reportUnusable(expression, role, "POSITION", diagnostics);
                } else if (role == Role.VAL && value.value() instanceof Value.Number) {
                    report(invalid(expression, "VAL", "is a number without a type", EXPECTED_BY_VAL),
                            expression.sourceInfo(), diagnostics);
                } else if (role == Role.TARGET) {
                    reportUnusable(expression, role, value.value() instanceof Value.Vector ? "VECTOR" : "LITERAL", diagnostics);
                }
            }
            case EvaluationResult.Partial partial -> {
                symbolTable.recordResult(expression, ExpressionOutcome.later(partial.expression(), partial.kind()));
                reportUnusable(expression, role, partial.kind() == Kind.POSITION ? "POSITION" : null, diagnostics);
            }
            case EvaluationResult.Error error ->
                    report(error.reason(), error.at() != null ? error.at() : expression.sourceInfo(), diagnostics);
            case EvaluationResult.Reported reported -> {
                // The error stands at the definition the computation uses
            }
        }
        return result;
    }

    /**
     * The message for an argument of a CALL that its kind does not take: the argument as written,
     * what it is, and what the kind expects.
     */
    private static String invalid(AstNode argument, String kind, String whatItIs, String expected) {
        String text = argument instanceof OperandNode operand ? OperandText.of(operand) : "?";
        return "Invalid argument: '" + text + "' for " + kind + " " + whatItIs + "; expected " + expected + ".";
    }

    /**
     * Reports a CALL target that is no name, of the kind given.
     */
    private static void reportWrongTarget(CallNode callNode, String kind, DiagnosticsEngine diagnostics) {
        diagnostics.reportError("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got " + kind + ".",
                callNode.sourceInfo().fileName(), callNode.sourceInfo().lineNumber());
    }

    /**
     * Names the kind of a written target that is no name: a literal, a vector or a register.
     */
    private String kindOf(AstNode target) {
        if (target instanceof LiteralNode literal) {
            return literal.value() instanceof Value.Vector ? "VECTOR" : "LITERAL";
        }
        if (target instanceof RegisterNode register) {
            return isLocationRegister(register.name()) ? "LOCATION_REGISTER" : "REGISTER";
        }
        return "LITERAL";
    }

    /**
     * Reports a computation whose value cannot stand where it stands: of the kind given, a
     * molecule type or a position, or, without a kind, computed from a position.
     */
    private static void reportUnusable(ExpressionNode expression, Role role, String kind, DiagnosticsEngine diagnostics) {
        String message = switch (role) {
            case TARGET -> kind != null
                    ? "Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got " + kind + "."
                    : "Argument 1 for instruction 'CALL' is computed from a position.";
            case VAL -> invalid(expression, "VAL", kind == null ? "is computed from a position"
                    : "TYPE".equals(kind) ? "is a molecule type" : "is a position", EXPECTED_BY_VAL);
        };
        report(message, expression.sourceInfo(), diagnostics);
    }

    private static void report(String message, SourceInfo at, DiagnosticsEngine diagnostics) {
        diagnostics.reportError(message, at.fileName(), at.lineNumber());
    }

    /**
     * Tells whether a register name resolves to a register of a location bank.
     */
    private boolean isLocationRegister(String registerName) {
        return isa.parseRegister(registerName).map(ref -> ref.bank().location()).orElse(false);
    }
}
