package org.evochora.compiler.backend.link;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.layout.PlacedItem;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrProgram;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.runtime.model.EnvironmentProperties;

import java.util.ArrayList;
import java.util.List;

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

        for (PlacedItem placed : layout.placedItems()) {
            IrItem item = placed.item();

            if (item instanceof IrDirective dir) {
                directiveRegistry.resolve(dir).handle(dir, context);
            }

            if (item instanceof IrInstruction ins) {
                // The address comes from the layout, which assigned it. Counting along here would
                // mean deciding a second time how many cells each item occupies.
                context.setCurrentAddress(placed.linearAddress());

                for (ILinkingRule rule : registry.rules()) {
                    ins = rule.apply(ins, context, layout);
                }
                out.add(ins);
            } else {
                out.add(item);
            }
        }
        return new IrProgram(program.programName(), out);
    }
}