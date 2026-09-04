package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.SourceInfo;

/**
 * An entry of the IR stream, one of exactly three kinds: an instruction, which occupies cells
 * according to its ISA signature; a label definition, which occupies one cell and is a jump
 * target; a directive, which occupies no cell and is handled by whatever a feature registered
 * for it in each backend phase.
 * <p>
 * The kinds are fixed by the core, because each needs the backend to know what to do with it.
 * A feature extends the IR through directives, which carry a namespace, a name and arguments
 * and are dispatched by name, or by subtyping {@link IrInstruction} to carry more than the
 * main operands, as the marshalling of procedure calls does.
 */
public sealed interface IrItem permits IrInstruction, IrLabelDef, IrDirective {

	/**
	 * Returns where in the source this item comes from.
	 *
	 * @return the source location, or {@code null} for an item without one
	 */
	SourceInfo source();
}
