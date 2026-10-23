package org.evochora.compiler.features.importdir;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;

/**
 * The definition behind the constant a value requirement defines in its placement: it binds a
 * use of the name to the value the importer bound, or to the default. When that value is a
 * computation, phase 4 records the constant's value for this node, and a use of the name stands
 * for the recorded value instead (see
 * {@link org.evochora.compiler.model.symbols.SymbolTable#chainEndOf}).
 *
 * @param value The bound value or the default; {@code null} when the placement received neither,
 *              which {@link RequireAnalysisHandler} or {@link ImportAnalysisHandler} has reported.
 */
public record ValueBindingNode(OperandNode value) implements AstNode, IIdentifierBinding {

    /**
     * Replaces a use of the value by the value. A value that is an identifier names a constant
     * where it is written, in the importer for a bound value, in the module for a default, and
     * is resolved again by the caller.
     */
    @Override
    public AstNode bind(IdentifierNode reference) {
        return value;
    }
}
