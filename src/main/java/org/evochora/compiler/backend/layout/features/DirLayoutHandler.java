package org.evochora.compiler.backend.layout.features;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

/**
 * Handles core:dir directive during layout.
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
