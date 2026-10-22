package org.evochora.compiler.features.instruction;

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
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.InstructionNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.IInstructionSet.ArgKind;

import java.util.Optional;

/**
 * Handles the semantic analysis of {@link InstructionNode}s.
 * This involves checking the instruction's arity, argument types, and other constraints.
 * <p>
 * A computation in an operand is evaluated here and its outcome recorded in the symbol table.
 * Its value is checked against the slot as a written literal is. A position and a molecule type
 * are of the wrong type for every slot; a value computed from a position, which only the layout
 * knows, is no argument either, written or through a name, at a label slot too.
 */
public class InstructionAnalysisHandler implements IAnalysisHandler {

    private final IInstructionSet isa;
    private final Evaluator evaluator;

    /**
     * Creates the handler for an instruction set.
     *
     * @param isa        The instruction set the instructions are checked against.
     * @param operations The operations of computations, read when the phase runs.
     */
    public InstructionAnalysisHandler(IInstructionSet isa, OperationRegistry operations) {
        this.isa = isa;
        this.evaluator = new Evaluator(operations);
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
                Slot slot = new Slot(i, expectedType, instructionNode);

                if (argumentNode instanceof ExpressionNode expression) {
                    checkComputation(expression, slot, symbolTable, diagnostics);
                } else if (argumentNode instanceof IdentifierNode idNode) {
                    // An identifier is checked by what it stands for
                    checkIdentifier(idNode, slot, symbolTable, diagnostics);
                } else {
                    checkOperand(argumentNode, slot, diagnostics);
                }
            }
        }
    }

    /**
     * The slot an operand stands in: its index, the kind of argument it expects, and the
     * instruction, whose line the type errors are reported at.
     */
    private record Slot(int index, ArgKind expected, InstructionNode instruction) {
    }

    /**
     * Evaluates a computation, records its outcome, and checks its value against the slot.
     */
    private void checkComputation(ExpressionNode expression, Slot slot, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        EvaluationResult result = evaluator.evaluate(expression.expression(),
                new SymbolTableResolution(symbolTable), IEvaluationContext.none());
        switch (result) {
            case EvaluationResult.Value value -> {
                symbolTable.recordResult(expression, ExpressionOutcome.of(value.value()));
                checkValue(value.value(), expression.sourceInfo(), slot, diagnostics);
            }
            case EvaluationResult.Partial partial -> {
                symbolTable.recordResult(expression, ExpressionOutcome.later(partial.expression(), partial.kind()));
                reportLayoutValue(partial.kind(), slot, diagnostics);
            }
            case EvaluationResult.Error error ->
                    report(error.reason(), error.at() != null ? error.at() : expression.sourceInfo(), diagnostics);
            case EvaluationResult.Reported reported -> {
                // The error stands at the definition the computation uses
            }
        }
    }

    /**
     * Checks an identifier by what the node that defined it says it stands for: a binding as
     * the argument it binds to, a jump target as a label, a definition whose value only the
     * layout knows not at all; anything else is no argument.
     */
    private void checkIdentifier(IdentifierNode idNode, Slot slot, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        switch (symbolTable.resolve(idNode.text(), idNode.sourceInfo())) {
            case ResolvedSymbol resolved -> {
                AstNode definition = resolved.symbol().node();
                if (definition instanceof IIdentifierBinding || definition instanceof IJumpTarget) {
                    // The symbol table follows the definitions; a circle it reports itself
                    Optional<SymbolTable.ChainEnd> end = symbolTable.chainEndOf(idNode);
                    if (end.isEmpty()) {
                        return;
                    }
                    Optional<ExpressionOutcome> laidOut = end.get().symbol() == null ? Optional.empty()
                            : symbolTable.definitionValueOf(end.get().node()).filter(outcome -> outcome.remaining() != null);
                    if (laidOut.isPresent()) {
                        reportLayoutValue(laidOut.get().kind(), slot, diagnostics);
                        return;
                    }
                    AstNode bound = end.get().symbol() != null
                            ? new IdentifierNode(end.get().qualifiedName(), end.get().sourceInfo())
                            : end.get().node();
                    // A constant is held to the same rules as a written operand
                    checkOperand(bound, slot, diagnostics);
                } else {
                    diagnostics.reportError(
                            String.format("'%s' is not a value, a register or a label and cannot be an instruction argument.",
                                    idNode.text()),
                            slot.instruction().sourceInfo().fileName(),
                            slot.instruction().sourceInfo().lineNumber()
                    );
                }
            }
            case Resolution.Missing missing -> diagnostics.reportError(
                    "Cannot use '" + idNode.text() + "' as an argument: " + missing.explanation(),
                    idNode.sourceInfo().fileName(),
                    idNode.sourceInfo().lineNumber()
            );
        }
    }

    /**
     * Checks a register, a literal or a label reference against the slot.
     */
    private void checkOperand(AstNode argument, Slot slot, DiagnosticsEngine diagnostics) {
        if (argument instanceof LiteralNode literal) {
            checkValue(literal.value(), literal.sourceInfo(), slot, diagnostics);
            return;
        }
        reportWrongType(slot, getArgumentTypeFromNode(argument), diagnostics);
    }

    /**
     * Checks a value against the slot: a vector is a vector argument, a number or a typed value a
     * literal argument, which has to carry a molecule type, written or reached through a constant
     * or a computation alike; a molecule type and a position are no argument at all.
     */
    private static void checkValue(Value value, SourceInfo at, Slot slot, DiagnosticsEngine diagnostics) {
        switch (value) {
            case Value.Type type -> reportWrongType(slot, "TYPE", diagnostics);
            case Value.Position position -> reportWrongType(slot, "POSITION", diagnostics);
            case Value.Vector vector -> reportWrongType(slot, ArgKind.VECTOR, diagnostics);
            case Value.Typed typed -> reportWrongType(slot, ArgKind.LITERAL, diagnostics);
            case Value.Number number -> {
                reportWrongType(slot, ArgKind.LITERAL, diagnostics);
                if (slot.expected() == ArgKind.LITERAL) {
                    diagnostics.reportError(
                            String.format("Argument %d for instruction '%s' requires a typed literal (e.g., DATA:42).",
                                    slot.index() + 1, slot.instruction().opcode()),
                            slot.instruction().sourceInfo().fileName(),
                            slot.instruction().sourceInfo().lineNumber()
                    );
                }
            }
        }
    }

    /**
     * Reports a value only the layout knows: a position as of the wrong type, any other value as
     * computed from a position.
     */
    private static void reportLayoutValue(Kind kind, Slot slot, DiagnosticsEngine diagnostics) {
        if (kind == Kind.POSITION) {
            reportWrongType(slot, "POSITION", diagnostics);
            return;
        }
        diagnostics.reportError(
                String.format("Argument %d for instruction '%s' is computed from a position.",
                        slot.index() + 1, slot.instruction().opcode()),
                slot.instruction().sourceInfo().fileName(),
                slot.instruction().sourceInfo().lineNumber()
        );
    }

    /**
     * Reports a value that is of no argument kind at all, a position or a molecule type, as of
     * the wrong type, named by its kind.
     */
    private static void reportWrongType(Slot slot, String actualType, DiagnosticsEngine diagnostics) {
        diagnostics.reportError(
                String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got %s.",
                        slot.index() + 1, slot.instruction().opcode(), slot.expected(), actualType),
                slot.instruction().sourceInfo().fileName(),
                slot.instruction().sourceInfo().lineNumber()
        );
    }

    private static void reportWrongType(Slot slot, ArgKind actualType, DiagnosticsEngine diagnostics) {
        if (slot.expected() != actualType) {
            diagnostics.reportError(
                    String.format("Argument %d for instruction '%s' has the wrong type. Expected %s, but got %s.",
                            slot.index() + 1, slot.instruction().opcode(), slot.expected(), actualType),
                    slot.instruction().sourceInfo().fileName(),
                    slot.instruction().sourceInfo().lineNumber()
            );
        }
    }

    private static void report(String message, SourceInfo at, DiagnosticsEngine diagnostics) {
        diagnostics.reportError(message, at.fileName(), at.lineNumber());
    }

    private ArgKind getArgumentTypeFromNode(AstNode node) {
        if (node instanceof RegisterNode regNode) {
            return isa.parseRegister(regNode.name()).map(ref -> ref.bank().location()).orElse(false)
                    ? ArgKind.LOCATION_REGISTER : ArgKind.REGISTER;
        }
        if (node instanceof LiteralNode literal) {
            return literal.value() instanceof Value.Vector ? ArgKind.VECTOR : ArgKind.LITERAL;
        }
        if (node instanceof IdentifierNode) return ArgKind.LABEL;
        throw new IllegalArgumentException("Unsupported argument node type: " + node.getClass().getSimpleName());
    }
}
