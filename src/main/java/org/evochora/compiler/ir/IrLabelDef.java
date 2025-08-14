package org.evochora.compiler.ir;

import org.evochora.compiler.api.SourceInfo;

/**
 * Label definition in the IR stream.
 */
public record IrLabelDef(String name, SourceInfo source) implements IrItem {}


