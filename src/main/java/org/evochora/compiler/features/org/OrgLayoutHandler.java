package org.evochora.compiler.features.org;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.Arrays;
import java.util.List;

/**
 * Layout handler for the {@code core:org} IR directive (Phase 9). Sets the current layout
 * position, taking each component either from the base position of the enclosing module context
 * or, where the component was marked relative, from the layout cursor.
 * <p>
 * The directive carries the position as a vector and, under {@code relative}, one flag per
 * component of it. Both are required, and the flags are as many as the components.
 */
public final class OrgLayoutHandler implements ILayoutDirectiveHandler {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
		IrValue.Vector vec = (IrValue.Vector) directive.args().get("position");
		List<IrValue> relative = ((IrValue.ListVal) directive.args().get("relative")).elements();
		int[] comps = vec.components();

		int[] basePos = context.basePos();
		// Reported here rather than at the first cell placed afterwards, because that cell can
		// stand many lines below the directive that sent the cursor there.
		if (comps.length != basePos.length) {
			throw new CompilationException(SourceInfo.locate(directive.source(), String.format(
					"Origin %s has %d components, the world has %d dimensions.",
					Arrays.toString(comps), comps.length, basePos.length)));
		}

		int[] cursor = context.currentPos();
		int[] newPos = new int[comps.length];
		for (int i = 0; i < comps.length; i++) {
			boolean marked = ((IrValue.Bool) relative.get(i)).value();
			// A relative component continues from where the last cell was placed; an absolute one
			// counts from the position the enclosing module was placed at.
			newPos[i] = (marked ? cursor[i] : basePos[i]) + comps[i];
		}
		context.setCurrentPos(newPos);
	}
}
