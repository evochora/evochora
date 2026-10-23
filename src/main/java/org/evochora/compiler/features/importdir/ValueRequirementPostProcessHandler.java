package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.postprocess.IPostProcessContext;
import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.LiteralNode;
import org.evochora.compiler.model.symbols.ExpressionOutcome;

/**
 * Phase-6 handler for value requirements whose value is a computation.
 *
 * <p>Phase 4 recorded the value of such a requirement for the symbol it defines: the value of a
 * computation, the tree of a default or a configured value only the layout computes, or, for a
 * bound computation only the layout computes, the requirement's own qualified name. The handler
 * replaces the whole node by one whose value slot carries that record, a literal or a
 * computation; replacing the whole node keeps the phase's own replacement of the identifier in
 * the value slot from taking its place. A requirement without a recorded value is left to the
 * phase, which replaces the identifier in its value slot by what the name leads to.</p>
 */
public final class ValueRequirementPostProcessHandler implements IPostProcessHandler {

    @Override
    public void collect(AstNode node, IPostProcessContext context) {
        ValueRequirementNode requirement = (ValueRequirementNode) node;
        context.definitionValue(requirement.name(), requirement.sourceInfo()).ifPresent(outcome ->
                context.replace(requirement, new ValueRequirementNode(requirement.name(), valueOf(requirement, outcome),
                        requirement.defaultValue(), requirement.configured(), requirement.configuredText(),
                        requirement.exported(), requirement.sourceInfo())));
    }

    /**
     * Builds the value slot from the recorded value, at the position of the requirement's name.
     * A tree ends where it ends itself: the configured value's or the default's after that
     * computation's last token, so that what the layout computes of it stands behind it; the
     * requirement's own name, recorded for a binding, after the name.
     */
    private static AstNode valueOf(ValueRequirementNode requirement, ExpressionOutcome outcome) {
        if (outcome.value() != null) {
            return new LiteralNode(outcome.value(), requirement.sourceInfo());
        }
        return new ExpressionNode(outcome.remaining(), requirement.sourceInfo());
    }
}
