package org.evochora.compiler.features.dir;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

/**
 * Layout handler for the {@code core:dir} IR directive (Phase 9). Sets the direction in which
 * subsequent instructions are placed, either from the vector the directive carries or by rotating
 * the direction currently in effect by 90 degrees.
 */
public final class DirLayoutHandler implements ILayoutDirectiveHandler {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
		IrValue.Vector vec = (IrValue.Vector) directive.args().get("direction");
		if (vec != null) {
			context.setCurrentDv(Nd.copy(vec.components()));
			return;
		}
		rotate(directive, context);
	}

	private void rotate(IrDirective directive, LayoutContext context) throws CompilationException {
		boolean forward = ((IrValue.Bool) directive.args().get("forward")).value();
		int axisA = (int) ((IrValue.Int64) directive.args().get("axisA")).value();
		int axisB = (int) ((IrValue.Int64) directive.args().get("axisB")).value();

		int[] dv = context.currentDv();
		if (dv.length < 2) {
			throw new CompilationException(SourceInfo.locate(directive.source(),
					"A rotation needs a plane, which a one-dimensional world does not have. "
					+ "Write the direction out instead."));
		}
		if (axisA == axisB) {
			throw new CompilationException(SourceInfo.locate(directive.source(),
					String.format("A rotation plane needs two different axes, both are %d.", axisA)));
		}
		for (int axis : new int[]{axisA, axisB}) {
			if (axis < 0 || axis >= dv.length) {
				throw new CompilationException(SourceInfo.locate(directive.source(), String.format(
						"Axis %d is not an axis of a %d-dimensional world.", axis, dv.length)));
			}
		}

		// Turning the component on axisA towards axisB, which is what the marker '@+' reads as;
		// a direction without a component in that plane is left as it is.
		int[] rotated = Nd.copy(dv);
		if (forward) {
			rotated[axisA] = -dv[axisB];
			rotated[axisB] = dv[axisA];
		} else {
			rotated[axisA] = dv[axisB];
			rotated[axisB] = -dv[axisA];
		}
		context.setCurrentDv(rotated);
	}
}
