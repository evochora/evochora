package org.evochora.compiler.backend.layout.features;

import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

/**
 * Handles core:org directive during layout.
 */
public final class OrgLayoutHandler implements ILayoutDirectiveHandler {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void handle(IrDirective directive, LayoutContext context) {
		IrValue.Vector vec = (IrValue.Vector) directive.args().get("position");
		// .ORG is relative to the current base position (which is set by includes)
		int[] newPos = Nd.add(context.basePos(), vec.components());
		context.setAnchorPos(newPos);
		context.setCurrentPos(newPos);
	}
}


