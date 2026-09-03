package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.Config;

/**
 * Label definition in the IR stream.
 * <p>
 * In the machine code a label is not a name but a value: the LABEL molecule carries it, a
 * LABELREF operand carries the value of the label it refers to, and the runtime matches the
 * two by Hamming distance. {@link #valueOf(String)} is the one place that turns a name into
 * that value, for definitions and references alike.
 *
 * @param name Label name.
 * @param source Source info location.
 */
public record IrLabelDef(String name, SourceInfo source) implements IrItem {

    /**
     * Mask of the bits a label value may use. A molecule value has {@link Config#VALUE_BITS}
     * bits in two's complement, and a label value must stay non-negative, so it uses one bit
     * less.
     */
    public static final int VALUE_MASK = (1 << (Config.VALUE_BITS - 1)) - 1;

    /**
     * Returns the value that stands for a label name in the machine code.
     *
     * @param name the label name, qualified as it is in the layout
     * @return the name's hash code reduced to {@link #VALUE_MASK}; never negative
     */
    public static int valueOf(String name) {
        return name.hashCode() & VALUE_MASK;
    }

    /**
     * Returns the value that stands for this label in the machine code.
     *
     * @return {@link #valueOf(String)} of the label's name
     */
    public int value() {
        return valueOf(name);
    }
}
