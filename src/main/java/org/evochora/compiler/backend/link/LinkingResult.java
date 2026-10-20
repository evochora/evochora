package org.evochora.compiler.backend.link;

import org.evochora.compiler.model.ir.IrProgram;

import java.util.Map;

/**
 * What the linker returns: the linked program, and the call-site bindings the linking rules
 * recorded, keyed by the address of the call and mapping each formal register to the caller's
 * register. The emitter puts them into the artifact unchanged.
 *
 * @param program          The linked program.
 * @param callSiteBindings The bindings per call address; unmodifiable.
 */
public record LinkingResult(IrProgram program, Map<Integer, Map<Integer, Integer>> callSiteBindings) {
    /**
     * Keeps an unmodifiable copy of the bindings.
     */
    public LinkingResult {
        callSiteBindings = Map.copyOf(callSiteBindings);
    }
}
