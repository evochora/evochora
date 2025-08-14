package org.evochora.compiler.ir;

import java.util.List;

/**
 * Linear IR program container. The order of items is the emission order
 * as produced by the frontend and should be preserved by backends.
 */
public record IrProgram(String programName, List<IrItem> items) {}


