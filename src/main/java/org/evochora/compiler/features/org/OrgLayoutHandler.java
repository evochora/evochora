package org.evochora.compiler.features.org;

import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.model.expression.Value;
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
 * position from the components the directive carries, each computed at this line.
 * <p>
 * A directive of one component may give the whole origin: a position is that place of the
 * program and becomes the cursor as it is; a vector counts from the base position of the
 * enclosing module context or, where the component was marked relative, from the layout cursor,
 * backwards where the marker was {@code @-}.
 * Otherwise every component is a number, a single one as a vector of one component, taken from
 * the base position or, marked relative, from the cursor along its own axis.
 * <p>
 * The directive carries the components under {@code components} and, under {@code relative} and
 * {@code backward}, one flag per component each. All three are required, and the flags are as many
 * as the components.
 */
public final class OrgLayoutHandler implements ILayoutDirectiveHandler {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
		List<IrValue> components = ((IrValue.ListVal) directive.args().get("components")).elements();
		List<IrValue> relative = ((IrValue.ListVal) directive.args().get("relative")).elements();
		List<IrValue> backward = ((IrValue.ListVal) directive.args().get("backward")).elements();
		SourceInfo src = directive.source();

		if (components.size() == 1) {
			Value whole = context.evaluate(components.getFirst(), src);
			boolean marked = ((IrValue.Bool) relative.getFirst()).value();
			if (whole instanceof Value.Position && !marked) {
				context.setCurrentPos(checked(Nd.coordinate(whole, src), context, src));
				return;
			}
			if (whole instanceof Value.Vector) {
				int[] comps = checked(Nd.coordinate(whole, src), context, src);
				if (((IrValue.Bool) backward.getFirst()).value()) {
					comps = Arrays.stream(comps).map(c -> -c).toArray();
				}
				// A vector is a place relative to the origin of the module, or to the cursor when marked
				context.setCurrentPos(Nd.add(marked ? context.currentPos() : context.basePos(), comps));
				return;
			}
			if (!(whole instanceof Value.Number)) {
				throw new CompilationException(SourceInfo.locate(src, "Expected a vector."));
			}
		}

		long[] values = new long[components.size()];
		for (int i = 0; i < values.length; i++) {
			if (!(context.evaluate(components.get(i), src) instanceof Value.Number number)) {
				throw new CompilationException(SourceInfo.locate(src, "Expected a number."));
			}
			values[i] = number.value();
		}
		int[] comps = checked(Nd.coordinate(Arrays.stream(values).boxed().toList(), src), context, src);

		int[] basePos = context.basePos();
		int[] cursor = context.currentPos();
		int[] newPos = new int[comps.length];
		for (int i = 0; i < comps.length; i++) {
			boolean marked = ((IrValue.Bool) relative.get(i)).value();
			// A relative component continues from where the last cell was placed; an absolute one
			// counts from the position the enclosing module was placed at.
			int offset = ((IrValue.Bool) backward.get(i)).value() ? -comps[i] : comps[i];
			newPos[i] = (marked ? cursor[i] : basePos[i]) + offset;
		}
		context.setCurrentPos(newPos);
	}

	/**
	 * Checks that an origin has a component per dimension of the world. Reported here rather than
	 * at the first cell placed afterwards, because that cell can stand many lines below the
	 * directive that sent the cursor there.
	 */
	private static int[] checked(int[] comps, LayoutContext context, SourceInfo src) throws CompilationException {
		int dims = context.basePos().length;
		if (comps.length != dims) {
			throw new CompilationException(SourceInfo.locate(src, String.format(
					"Origin %s has %d components, the world has %d dimensions.",
					Arrays.toString(comps), comps.length, dims)));
		}
		return comps;
	}
}
