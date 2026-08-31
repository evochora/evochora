package org.evochora.compiler.model.ir;

import java.util.List;

/**
 * Linear IR program container. The order of items is the emission order
 * as produced by the frontend and should be preserved by backends.
 *
 * @param programName Name of the program.
 * @param items Sequential list of IR items.
 */
public record IrProgram(String programName, List<IrItem> items) {}


