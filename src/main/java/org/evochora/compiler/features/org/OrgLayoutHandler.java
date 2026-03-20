package org.evochora.compiler.features.org;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Layout handler for the {@code core:org} IR directive (Phase 9). Sets the
 * current layout position to the specified origin vector, relative to the
 * current base position established by the enclosing module context.
 */
public final class OrgLayoutHandler implements ILayoutDirectiveHandler {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
		IrValue.Vector vec = (IrValue.Vector) directive.args().get("position");
		// .ORG is relative to the current base position (which is set by includes)
		int[] newPos = Nd.add(context.basePos(), vec.components());
		context.setAnchorPos(newPos);
		context.setCurrentPos(newPos);
	}
}
