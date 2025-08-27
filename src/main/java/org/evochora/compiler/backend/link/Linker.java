package org.evochora.compiler.backend.link;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrProgram;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Linking pass: resolves symbolic references using the layout result. Parameter binding is collected
 * as metadata only at this stage.
 */
public final class Linker {

    private final LinkingRegistry registry;

    /**
     * Constructs a new linker.
     * @param registry The registry of linking rules to apply.
     */
    public Linker(LinkingRegistry registry) { this.registry = registry; }

    /**
     * Links the given IR program, resolving symbolic references.
     * @param program The IR program to link.
     * @param layout The layout result, providing coordinate and address mappings.
     * @param context The linking context, which will be populated with call site bindings.
     * @param worldDimensions The number of dimensions in the world.
     * @return The linked IR program.
     */
    public IrProgram link(IrProgram program, LayoutResult layout, LinkingContext context, int worldDimensions) {
        List<IrItem> out = new ArrayList<>();
        // We need the ISA definitions to know the size of the arguments.
        org.evochora.compiler.isa.IInstructionSet isa = new org.evochora.compiler.isa.RuntimeInstructionSetAdapter();

        for (IrItem item : program.items()) {
            if (item instanceof IrInstruction ins) {
                // NEW: Check if bindings exist for this CALL instruction
                if ("CALL".equalsIgnoreCase(ins.opcode())) {
                    int[] bindings = context.resolvePendingBinding(ins, isa);
                    if (bindings != null) {
                        // Link the bindings with the current (final) linear address
                        context.callSiteBindings().put(context.currentAddress(), bindings);
                    }
                }

                for (ILinkingRule rule : registry.rules()) {
                    ins = rule.apply(ins, context, layout);
                }
                out.add(ins);

                // *** CORRECT logic for counting addresses ***
                context.nextAddress(); // First, count for the opcode itself.

                Integer opcodeId = isa.getInstructionIdByName(ins.opcode()).orElse(null);
                if (opcodeId != null) {
                    Optional<IInstructionSet.Signature> sigOpt = isa.getSignatureById(opcodeId);
                    if (sigOpt.isPresent()) {
                        // Determine the size of each argument based on the signature
                        for (org.evochora.compiler.isa.IInstructionSet.ArgKind kind : sigOpt.get().argumentTypes()) {
                            if (kind == org.evochora.compiler.isa.IInstructionSet.ArgKind.VECTOR || kind == org.evochora.compiler.isa.IInstructionSet.ArgKind.LABEL) {
                                for (int k = 0; k < worldDimensions; k++) context.nextAddress();
                            } else {
                                context.nextAddress();
                            }
                        }
                    } else {
                        // Fallback if an instruction has no signature
                        int arity = ins.operands() != null ? ins.operands().size() : 0;
                        for (int i = 0; i < arity; i++) context.nextAddress();
                    }
                }
                // *** END OF CORRECTION ***

            } else {
                out.add(item);
            }
        }
        return new IrProgram(program.programName(), out);
    }
}