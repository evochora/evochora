package org.evochora.runtime.label;

import org.evochora.runtime.Config;

/**
 * The molecule types whose value is an address in the label namespace: a {@code LABEL}, which is
 * such an address, and a {@code LABELREF}, which names one.
 * <p>
 * Whatever moves an organism from one label namespace to another — the rewrite at birth, the
 * normalization of a genome hash, the translation of a recorded mutation — applies to exactly these
 * values and asks here, so that the set is written down once.
 */
public final class LabelAddress {

    private LabelAddress() {
        // Utility class
    }

    /**
     * Tells whether a molecule carries a label address.
     *
     * @param moleculeInt A packed molecule integer, or a molecule type constant; only the type
     *                    bits are used
     * @return {@code true} for a {@code LABEL} and a {@code LABELREF}
     */
    public static boolean isCarriedBy(int moleculeInt) {
        int type = moleculeInt & Config.TYPE_MASK;
        return type == Config.TYPE_LABEL || type == Config.TYPE_LABELREF;
    }
}
