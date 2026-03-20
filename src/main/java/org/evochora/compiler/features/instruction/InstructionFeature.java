package org.evochora.compiler.features.instruction;

import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.IFeatureRegistrationContext;
import org.evochora.compiler.model.ast.InstructionNode;

/**
 * Compiler feature for generic machine instructions (MOV, ADD, NOP, etc.).
 * Registers the default parser handler and handlers for semantic analysis,
 * token map generation, and IR conversion.
 */
public class InstructionFeature implements ICompilerFeature {

    @Override
    public String name() { return "instruction"; }

    @Override
    public void register(IFeatureRegistrationContext ctx) {
        ctx.defaultParserStatement(new InstructionParsingHandler());
        ctx.analysisHandler(InstructionNode.class, new InstructionAnalysisHandler());
        ctx.tokenMapContributor(InstructionNode.class, new InstructionTokenMapContributor());
        ctx.irConverter(InstructionNode.class, new InstructionNodeConverter());
    }
}
