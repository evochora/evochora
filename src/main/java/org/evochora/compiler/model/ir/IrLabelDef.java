package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.SourceInfo;

/**
 * Label definition in the IR stream.
 * <p>
 * In the machine code a label is not a name but a value: the LABEL molecule carries it, a
 * LABELREF operand carries the value of the label it refers to, and the runtime matches the
 * two by Hamming distance. The instruction set derives that value from the name, for
 * definitions and references alike.
 *
 * @param name Label name.
 * @param source Source info location.
 */
public record IrLabelDef(String name, SourceInfo source) implements IrItem {
}
