package org.evochora.compiler.expression.symbols;

import org.evochora.compiler.expression.INameResolution;
import org.evochora.compiler.expression.NameAnswer;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IJumpTarget;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.ast.RegisterNode;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.NameExpression;
import org.evochora.compiler.model.symbols.ExpressionOutcome;
import org.evochora.compiler.model.symbols.Resolution;
import org.evochora.compiler.model.symbols.ResolvedSymbol;
import org.evochora.compiler.model.symbols.SymbolTable;

import java.util.Optional;

/**
 * Answers the names of a computation from the symbol table, for every frontend handler that
 * evaluates. It asks the node that defined a name what the name stands for, never the kind of
 * the symbol, and follows a chain of names through {@link IIdentifierBinding} with
 * {@link SymbolTable#chainEndOf}, answering from where the chain ends:
 * <ul>
 *   <li>at a jump target, a label, a procedure or a control block: "later", a position, under the
 *       qualified name;</li>
 *   <li>at a definition whose recorded value only a later phase knows: "later", with the kind
 *       recorded, under the definition's qualified name;</li>
 *   <li>at a literal, written or the recorded value of a definition: its value;</li>
 *   <li>at a register: not usable;</li>
 *   <li>at a definition without a value, which its definition has reported: "reported";</li>
 *   <li>at anything else: not usable as a value.</li>
 * </ul>
 * The cursor {@code @} is "later, a position". A name is looked up from the scope the symbol
 * table currently stands in, so the caller sets the scope of the line before it evaluates.
 */
public final class SymbolTableResolution implements INameResolution {

    private final SymbolTable symbols;

    /**
     * Creates a resolution over a symbol table.
     *
     * @param symbols The symbol table, filled by the symbol collection of phase 4.
     */
    public SymbolTableResolution(SymbolTable symbols) {
        this.symbols = symbols;
    }

    @Override
    public NameAnswer resolve(NameExpression name) {
        Optional<NameAnswer> cursor = INameResolution.cursorLater(name);
        if (cursor.isPresent()) {
            return cursor.get();
        }
        return switch (symbols.resolve(name.name(), name.sourceInfo())) {
            case Resolution.Missing missing -> new NameAnswer.NotUsable(notDefined(name.name(), missing));
            case ResolvedSymbol resolved -> answer(name, resolved);
        };
    }

    private NameAnswer answer(NameExpression name, ResolvedSymbol resolved) {
        AstNode definition = resolved.symbol().node();
        if (definition instanceof IJumpTarget) {
            return new NameAnswer.Later(Kind.POSITION, resolved.qualifiedName());
        }
        if (!(definition instanceof IIdentifierBinding)) {
            return notAValue(name);
        }
        // The symbol table follows the definitions and reports a circle itself
        Optional<SymbolTable.ChainEnd> end = symbols.chainEndOf(new IdentifierNode(name.name(), name.sourceInfo()));
        if (end.isEmpty()) {
            return new NameAnswer.Reported();
        }
        if (end.get().symbol() != null) {
            if (end.get().node() instanceof IJumpTarget) {
                return new NameAnswer.Later(Kind.POSITION, end.get().qualifiedName());
            }
            Optional<ExpressionOutcome> recorded = symbols.definitionValueOf(end.get().node());
            return recorded.isPresent() && recorded.get().remaining() != null
                    ? new NameAnswer.Later(recorded.get().kind(), end.get().qualifiedName())
                    : notAValue(name);
        }
        return switch (end.get().node()) {
            case LiteralNode literal -> new NameAnswer.Value(literal.value());
            case RegisterNode ignored -> new NameAnswer.NotUsable(
                    "Register '" + name.name() + "' cannot be used in an expression.");
            default -> notAValue(name);
        };
    }

    private static String notDefined(String name, Resolution.Missing missing) {
        if (missing.undefined()) {
            return "'" + name + "' is not defined.";
        }
        return "Cannot use '" + name + "' in an expression: " + missing.explanation();
    }

    private static NameAnswer notAValue(NameExpression name) {
        return new NameAnswer.NotUsable("'" + name.name() + "' cannot be used as a value.");
    }
}
