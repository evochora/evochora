package org.evochora.compiler.backend.link;

import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Map<IrInstruction, List<String>> pendingBindings = new IdentityHashMap<>();
    private final Deque<String> aliasChainStack = new ArrayDeque<>();


    /**
     * @return The next linear address and increments the cursor.
     */
    public int nextAddress() { return linearAddressCursor++; }

    /**
     * @return The current linear address.
     */
    public int currentAddress() { return linearAddressCursor; }

    /**
     * @return The map of call site bindings.
     */
    public Map<Integer, int[]> callSiteBindings() { return callSiteBindings; }

    /**
     * Adds a pending binding for a CALL instruction.
     * @param call The CALL instruction.
     * @param registers The list of register names for the binding.
     */
    public void addPendingBinding(IrInstruction call, List<String> registers) {
        pendingBindings.put(call, registers);
    }

    /**
     * Resolves a pending binding for a CALL instruction.
     * @param call The CALL instruction.
     * @param isa The instruction set for resolving register names.
     * @return An array of register IDs, or null if no binding exists.
     */
    public int[] resolvePendingBinding(IrInstruction call, IInstructionSet isa) {
        List<String> regNames = pendingBindings.get(call);
        if (regNames == null) return null;
        int[] ids = new int[regNames.size()];
        for (int i = 0; i < regNames.size(); i++) {
            ids[i] = isa.resolveRegisterToken(regNames.get(i)).orElse(-1);
        }
        return ids;
    }

    // --- Alias chain stack for module context tracking ---

    /**
     * Pushes an alias chain when entering an imported module.
     */
    public void pushAliasChain(String aliasChain) {
        aliasChainStack.push(aliasChain);
    }

    /**
     * Pops the alias chain when leaving an imported module.
     */
    public void popAliasChain() {
        if (!aliasChainStack.isEmpty()) {
            aliasChainStack.pop();
        }
    }

    /**
     * Returns the current alias chain, or empty string if the stack is empty.
     */
    public String currentAliasChain() {
        return aliasChainStack.isEmpty() ? "" : aliasChainStack.peek();
    }
}