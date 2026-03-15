package org.evochora.compiler.backend.link;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrProgram;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Linking pass: dispatches registered {@link ILinkingRule} implementations to resolve
 * symbolic references and collect metadata. The linker itself is feature-agnostic.
 */
public final class Linker {

    private final LinkingRegistry registry;
    private final LinkingDirectiveRegistry directiveRegistry;

    /**
     * Constructs a new linker.
     * @param registry The registry of linking rules to apply to instructions.
     * @param directiveRegistry The registry of directive handlers for context mutations.
     */
    public Linker(LinkingRegistry registry, LinkingDirectiveRegistry directiveRegistry) {
        this.registry = registry;
        this.directiveRegistry = directiveRegistry;
    }

    /**
     * Links the given IR program, resolving symbolic references.
     * @param program The IR program to link.
     * @param layout The layout result, providing coordinate and address mappings.
     * @param context The linking context, which will be populated with call site bindings.
     * @param envProps The environment properties, providing context like world dimensions. Can be null.
     * @return The linked IR program.
     * @throws CompilationException if an error occurs during linking.
     */
    public IrProgram link(IrProgram program, LayoutResult layout, LinkingContext context, EnvironmentProperties envProps) throws CompilationException {
        List<IrItem> out = new ArrayList<>();
        IInstructionSet isa = new org.evochora.compiler.isa.RuntimeInstructionSetAdapter();

        for (IrItem item : program.items()) {
            if (item instanceof IrDirective dir) {
                directiveRegistry.resolve(dir).handle(dir, context);
            }

            if (item instanceof IrInstruction ins) {
                for (ILinkingRule rule : registry.rules()) {
                    ins = rule.apply(ins, context, layout);
                }
                out.add(ins);

                context.nextAddress();

                Integer opcodeId = isa.getInstructionIdByName(ins.opcode()).orElse(null);
                if (opcodeId != null) {
                    Optional<IInstructionSet.Signature> sigOpt = isa.getSignatureById(opcodeId);
                    if (sigOpt.isPresent()) {
                        for (IInstructionSet.ArgKind kind : sigOpt.get().argumentTypes()) {
                            if (kind == IInstructionSet.ArgKind.VECTOR) {
                                // VECTOR operands occupy worldDimensions slots (one per dimension)
                                if (envProps == null || envProps.getWorldShape() == null || envProps.getWorldShape().length == 0) {
                                    throw new CompilationException("Instruction " + ins.opcode() + " requires vector arguments, which need a world context, but no environment properties were provided.", ins.source());
                                }
                                int worldDimensions = envProps.getWorldShape().length;
                                for (int k = 0; k < worldDimensions; k++) context.nextAddress();
                            } else {
                                // REGISTER, LOCATION_REGISTER, LITERAL, LABEL → one slot each
                                // Note: LABEL used to be N-dimensional coordinates, now it's a single hash value
                                context.nextAddress();
                            }
                        }
                    } else {
                        int arity = ins.operands() != null ? ins.operands().size() : 0;
                        for (int i = 0; i < arity; i++) context.nextAddress();
                    }
                }
            } else {
                out.add(item);
            }
        }
        return new IrProgram(program.programName(), out);
    }
}