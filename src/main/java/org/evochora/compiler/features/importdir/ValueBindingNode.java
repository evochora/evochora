package org.evochora.compiler.features.importdir;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.OperandNode;

/**
 * The definition behind the constant a value requirement defines in its placement: it binds a
 * use of the name to the value the importer bound, to the configured value or to the default.
 * Pass 1 defines the constant with this node; pass 2 records the constant's value for it when
 * that value is a computation, and a use of the name stands for the recorded value instead (see
 * {@link org.evochora.compiler.model.symbols.SymbolTable#chainEndOf}). The requirement the node
 * stands for is carried along, so that the value can be computed when a name needs it, before
 * the walk reaches the requirement's line.
 *
 * @param value       The bound value, the configured value or the default; {@code null} when the
 *                    placement received none, which {@link RequireAnalysisHandler} or
 *                    {@link ImportAnalysisHandler} reports.
 * @param requirement The requirement that defines the constant.
 */
public record ValueBindingNode(OperandNode value, ValueRequirementNode requirement) implements AstNode, IIdentifierBinding {

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
