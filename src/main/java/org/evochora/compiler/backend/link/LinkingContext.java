package org.evochora.compiler.backend.link;

import org.evochora.compiler.isa.IInstructionSet;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context for the linking phase. Provides the instruction set to linking rules,
 * following the same pattern as IrGenContext and EmissionContext in other phases, and
 * collects what the rules find out about the items they link.
 */
public final class LinkingContext {

    private final IInstructionSet isa;
    private int linearAddressCursor = 0;
    private final Map<Integer, Map<Integer, Integer>> callSiteBindings = new HashMap<>();

    /**
     * Constructs a new linking context.
     *
     * @param isa The instruction set adapter for register resolution.
     */
    public LinkingContext(IInstructionSet isa) {
        this.isa = isa;
    }

    /**
     * Returns the instruction set a rule consults to turn a register name into its id.
     *
     * @return The instruction set adapter for register resolution.
     */
    public IInstructionSet isa() { return isa; }

    /**
     * Sets the address of the item being linked, as assigned by the layout phase.
     *
     * @param linearAddress The address of the item's first cell.
     */
    public void setCurrentAddress(final int linearAddress) { this.linearAddressCursor = linearAddress; }

    /**
     * Returns the address the linker is currently working at, as the layout assigned it.
     *
     * @return The linear address of the item currently being linked.
     */
    public int currentAddress() { return linearAddressCursor; }

    /**
     * Returns call site bindings. Outer key: linear address of the CALL instruction.
     * Inner map: formal register ID (FDR_BASE+i or FLR_BASE+i) to source register ID. The
     * rules fill it while the linker runs; the linker copies it into its result.
     *
     * @return the call site bindings map
     */
    public Map<Integer, Map<Integer, Integer>> callSiteBindings() {
        return callSiteBindings;
    }
}
