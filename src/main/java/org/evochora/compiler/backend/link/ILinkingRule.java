package org.evochora.compiler.backend.link;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.ir.IrInstruction;

/**
 * Linking rule that can transform an instruction (e.g., resolve label refs).
 */
public interface ILinkingRule {

	/**
	 * Applies linking on a single instruction, returning a potentially rewritten instruction.
	 *
	 * @param instruction   The original instruction.
	 * @param context       Linking context with layout information and linear address cursor.
	 * @param layout        The layout result providing coordinate/address mappings.
	 * @return The (potentially) rewritten instruction.
	 */
	IrInstruction apply(IrInstruction instruction, LinkingContext context, LayoutResult layout);
}


