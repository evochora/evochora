package org.evochora.compiler.backend.link;

import org.evochora.compiler.model.ir.IrProgram;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the linker returns: the linked program, and the call-site bindings the linking rules
 * recorded, keyed by the address of the call and mapping each formal register to the caller's
 * register. The emitter puts them into the artifact unchanged.
 *
 * @param program          The linked program.
 * @param callSiteBindings The bindings per call address; unmodifiable, the inner maps included,
 *                         each in the order of its keys.
 */
public record LinkingResult(IrProgram program, Map<Integer, Map<Integer, Integer>> callSiteBindings) {
    /**
     * Keeps an unmodifiable copy of the bindings, each call's map copied too, ordered by key, so
     * that the artifact lists them the same way in every run.
     */
    public LinkingResult {
        Map<Integer, Map<Integer, Integer>> ordered = new TreeMap<>();
        callSiteBindings.forEach((address, bindings) ->
                ordered.put(address, Collections.unmodifiableMap(new TreeMap<>(bindings))));
        callSiteBindings = Collections.unmodifiableMap(ordered);
    }
}
