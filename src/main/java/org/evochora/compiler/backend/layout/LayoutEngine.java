package org.evochora.compiler.backend.layout;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrLabelDef;
import org.evochora.compiler.model.ir.IrProgram;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Lays out IR items along an n-dimensional grid according to .ORG/.DIR directives.
 * This pass does not perform linking; label references are not resolved here.
 */
public final class LayoutEngine {

    /**
     * Lays out the given IR program in an n-dimensional grid.
     * @param program The IR program to lay out.
     * @param isa The instruction set for determining operand sizes.
     * @param envProps The environment properties, providing context like world dimensions. Can be null.
     * @return The result of the layout process.
     * @throws CompilationException if address conflicts are detected during layout.
     */
    public LayoutResult layout(IrProgram program, IInstructionSet isa, EnvironmentProperties envProps,
                               LayoutDirectiveRegistry registry) throws CompilationException {
        LayoutContext ctx = new LayoutContext(envProps);

        Map<String, Integer> labelToAddress = new HashMap<>();

        for (IrItem item : program.items()) {
            SourceInfo src = item.source();

            if (item instanceof IrDirective dir) {
                registry.resolve(dir).handle(dir, ctx);
                continue;
            }

            if (item instanceof IrLabelDef lbl) {
                labelToAddress.put(lbl.name(), ctx.linearAddress());
                ctx.placeLabel(src);  // Labels now occupy 1 cell in the grid
                continue;
            }

            if (item instanceof IrInstruction ins) {
                ctx.placeOpcode(src);

                int opcodeId = isa.getInstructionIdByName(ins.opcode()).orElseThrow(() -> new IllegalArgumentException("Unknown opcode: " + ins.opcode()));
                IInstructionSet.Signature sig = isa.getSignatureById(opcodeId)
                        .orElseThrow(() -> new IllegalArgumentException("No ISA signature for instruction '" + ins.opcode() + "'."));
                for (IInstructionSet.ArgKind kind : sig.argumentTypes()) {
                    if (kind == IInstructionSet.ArgKind.VECTOR) {
                        if (ctx.getEnvProps() == null || ctx.getEnvProps().getWorldShape() == null || ctx.getEnvProps().getWorldShape().length == 0) {
                            throw new CompilationException("Instruction " + ins.opcode() + " requires vector arguments, which need a world context, but no environment properties were provided.", src);
                        }
                        int dims = ctx.getEnvProps().getWorldShape().length;
                        for (int k = 0; k < dims; k++) {
                            ctx.placeOperand(src);
                        }
                    } else {
                        ctx.placeOperand(src);
                    }
                }
            }
        }

        return new LayoutResult(ctx.linearToCoord(), ctx.coordToLinear(), labelToAddress, ctx.sourceMap(), ctx.initialWorldObjects());
    }
}
