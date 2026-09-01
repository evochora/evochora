package org.evochora.compiler.backend.link;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.layout.PlacedItem;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrProgram;

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
     * Links the items the layout placed, resolving symbolic references.
     * <p>
     * The items come from the layout, which carries each one together with the address it was
     * given. Taking the program as well would offer a second source for the same items, and
     * nothing would stop the two from describing different programs.
     *
     * @param layout The placed items with their addresses, and the coordinate mappings.
     * @param context The linking context, which will be populated with call site bindings.
     * @param programName The name carried over to the linked program.
     * @return The linked IR program.
     * @throws CompilationException if an error occurs during linking.
     */
    public IrProgram link(LayoutResult layout, LinkingContext context, String programName) throws CompilationException {
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
        return new IrProgram(programName, out);
    }
}