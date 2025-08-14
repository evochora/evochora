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
	@Override
	public void handle(IrDirective directive, LayoutContext context) {
		IrValue.Vector vec = (IrValue.Vector) directive.args().get("position");
		// .ORG setzt den aktuellen Anker (absolut zum Programmstart)
		context.setAnchorPos(Nd.copy(vec.components()));
		context.setCurrentPos(Nd.copy(vec.components()));
	}
}


