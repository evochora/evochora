package org.evochora.compiler.backend.link;

import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

/**
 * Mutable context for the linking phase.
 */
public final class LinkingContext {

    private int linearAddressCursor = 0;
    private final Map<Integer, int[]> callSiteBindings = new HashMap<>();
    // NEU: Temporäre Map zur Speicherung von Bindungen vor dem Linken
    private final Map<IrInstruction, List<String>> pendingBindings = new IdentityHashMap<>();


    public int nextAddress() { return linearAddressCursor++; }
    public int currentAddress() { return linearAddressCursor; }

    public Map<Integer, int[]> callSiteBindings() { return callSiteBindings; }

    // NEUE Methoden
    public void addPendingBinding(IrInstruction call, List<String> registers) {
        pendingBindings.put(call, registers);
    }

    public int[] resolvePendingBinding(IrInstruction call, IInstructionSet isa) {
        List<String> regNames = pendingBindings.get(call);
        if (regNames == null) return null;
        int[] ids = new int[regNames.size()];
        for (int i = 0; i < regNames.size(); i++) {
            ids[i] = isa.resolveRegisterToken(regNames.get(i)).orElse(-1);
        }
        return ids;
    }
}