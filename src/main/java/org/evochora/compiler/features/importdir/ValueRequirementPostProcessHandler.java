package org.evochora.compiler.features.importdir;

import org.evochora.compiler.api.SourceInfo;
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
     * A tree that is the configured value's or the default's ends where that computation ends, so
     * that what the layout computes of it stands behind its last token; the requirement's own name,
     * recorded for a binding, ends after the name.
     */
    private static AstNode valueOf(ValueRequirementNode requirement, ExpressionOutcome outcome) {
        if (outcome.value() != null) {
            return new LiteralNode(outcome.value(), requirement.sourceInfo());
        }
        return new ExpressionNode(outcome.remaining(), requirement.sourceInfo(), endOf(requirement, outcome));
    }

    /**
     * The position right after the computation the recorded tree was made of: the configured
     * value or the default whose tree starts where the recorded one does, else the requirement's
     * name.
     */
    private static SourceInfo endOf(ValueRequirementNode requirement, ExpressionOutcome outcome) {
        SourceInfo start = outcome.remaining().sourceInfo();
        for (AstNode given : new AstNode[]{requirement.configured(), requirement.defaultValue()}) {
            if (given instanceof ExpressionNode expression && expression.expression().sourceInfo().equals(start)) {
                return expression.end();
            }
        }
        return requirement.sourceInfo().plusColumns(requirement.name().length());
    }
}
