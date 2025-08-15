package org.evochora.compiler.backend.layout.features;

import org.evochora.runtime.Config;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;

/**
 * Handles core:place directive during layout.
 */
public final class PlaceLayoutHandler implements ILayoutDirectiveHandler {
	@Override
	public void handle(IrDirective directive, LayoutContext context) {
		IrValue.Vector vec = (IrValue.Vector) directive.args().get("position");
		int[] coord = Nd.add(context.basePos(), vec.components());
		IrValue val = directive.args().get("value");
		IrValue.Str t = (IrValue.Str) directive.args().get("type");
		int type;
		String ts = t != null ? t.value() : "DATA";
		switch (ts.toUpperCase()) {
			case "CODE" -> type = Config.TYPE_CODE;
			case "ENERGY" -> type = Config.TYPE_ENERGY;
			case "STRUCTURE" -> type = Config.TYPE_STRUCTURE;
			default -> type = Config.TYPE_DATA;
		}
		long value = val instanceof IrValue.Int64 iv ? iv.value() : 0L;
		context.initialWorldObjects().put(coord, new PlacedMolecule(type, (int) value));
	}
}



