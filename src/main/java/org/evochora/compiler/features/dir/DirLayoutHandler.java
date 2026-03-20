package org.evochora.compiler.features.dir;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Layout handler for the {@code core:dir} IR directive (Phase 9). Sets the
 * current direction vector on the layout context, controlling the direction
 * in which subsequent instructions are placed.
 */
public final class DirLayoutHandler implements ILayoutDirectiveHandler {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
		IrValue.Vector vec = (IrValue.Vector) directive.args().get("direction");
		context.setCurrentDv(Nd.copy(vec.components()));
	}
}
