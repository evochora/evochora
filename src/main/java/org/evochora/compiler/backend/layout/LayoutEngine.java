package org.evochora.compiler.backend.layout;

import org.evochora.app.setup.Config;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.ir.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Lays out IR items along an n-dimensional grid according to .ORG/.DIR directives.
 * This pass does not perform linking; label references are not resolved here.
 */
public final class LayoutEngine {

    public LayoutResult layout(IrProgram program, IInstructionSet isa) {
        int dims = 2; // Will be validated later
        LayoutContext ctx = new LayoutContext(dims);
        LayoutDirectiveRegistry registry = LayoutDirectiveRegistry.initializeWithDefaults();

        Map<String, Integer> labelToAddress = new HashMap<>();

		for (IrItem item : program.items()) {
			SourceInfo src = item.source();
            String file = src != null ? src.fileName() : null;
            if (file != null) ctx.onFileChanged(file);

			if (item instanceof IrDirective dir) {
                registry.resolve(dir).handle(dir, ctx);
                continue;
			}

            if (item instanceof IrLabelDef lbl) { labelToAddress.put(lbl.name(), ctx.linearAddress()); continue; }

            if (item instanceof IrInstruction ins) {
                // place opcode
                ctx.placeOpcode(src);

                // determine operand slot widths from ISA signature
                int opcodeId = isa.getInstructionIdByName(ins.opcode()).orElseThrow(() -> new IllegalArgumentException("Unknown opcode: " + ins.opcode()));
                var sigOpt = isa.getSignatureById(opcodeId);
                int argIndex = 0;
                if (sigOpt.isPresent()) {
                    for (IInstructionSet.ArgKind kind : sigOpt.get().argumentTypes()) {
                        if (kind == IInstructionSet.ArgKind.REGISTER || kind == IInstructionSet.ArgKind.LITERAL) {
                            ctx.placeOperand(src);
                            argIndex++;
                        } else if (kind == IInstructionSet.ArgKind.VECTOR || kind == IInstructionSet.ArgKind.LABEL) {
                            for (int k = 0; k < Config.WORLD_DIMENSIONS; k++) ctx.placeOperand(src);
                            argIndex++;
                        }
                    }
                } else {
                    // Fallback: assume one slot per provided operand
                    int arity = ins.operands() != null ? ins.operands().size() : 0;
                    for (int i = 0; i < arity; i++) ctx.placeOperand(src);
                }
			}
		}

        return new LayoutResult(ctx.linearToCoord(), ctx.coordToLinear(), labelToAddress, ctx.sourceMap(), ctx.initialWorldObjects());
	}
}


