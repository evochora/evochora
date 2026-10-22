package org.evochora.compiler.features.place;

import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout handler for the {@code core:place} IR directive (Phase 9). Computes the placements
 * (vectors, positions, ranges, wildcards) at this line and writes the resulting molecules into
 * the initial world object map at the computed coordinates. A vector counts from the base
 * position of the enclosing module, a position is a place of the program as it is.
 */
public final class PlaceLayoutHandler implements ILayoutDirectiveHandler {

    /** How a cell this directive places is named in a conflict message. */
    private static final String PLACED_BY = "a .PLACE";

    private final IInstructionSet isa;

    /**
     * Creates the handler for an instruction set.
     *
     * @param isa The instruction set, which names the molecule types a placement may use.
     */
    public PlaceLayoutHandler(IInstructionSet isa) {
        this.isa = isa;
    }

    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        PlacedMolecule molecule = createMolecule(directive, context);
        List<IrValue> placements = ((IrValue.ListVal) directive.args().get("placements")).elements();

        for (IrValue placement : placements) {
            List<IrValue> components = ((IrValue.ListVal) placement).elements();
            place(components, molecule, context, directive.source());
        }
    }

    /**
     * The molecule the directive places: its value computed at this line, a typed value whose
     * number has to fit a cell of its type. The layout never meets a typed value whose type the
     * instruction set does not know, because the typing operation only produces known types; the
     * check of the type is a safety net and reports an internal error.
     */
    private PlacedMolecule createMolecule(IrDirective directive, LayoutContext context) throws CompilationException {
        if (!(context.evaluate(directive.args().get("value"), directive.source()) instanceof Value.Typed typed)) {
            throw new CompilationException(SourceInfo.locate(directive.source(), "Expected a typed literal (e.g. DATA:5) for .PLACE."));
        }
        int type = isa.moleculeType(typed.type()).orElseThrow(() ->
                new CompilationException(SourceInfo.locate(directive.source(), "Internal error: Unknown molecule type '" + typed.type() + "' in .PLACE.")));
        isa.valueRange(type).require(typed.number(), directive.source());
        return new PlacedMolecule(type, (int) typed.number(), 0);
    }

    /**
     * Places the molecule on the cells of one placement, one cell at a time, so that the layout
     * checks each cell before the next is made. A placement of one single value whose value is a
     * vector is that place counted from the base position of the enclosing module, one whose
     * value is a position that place of the program; otherwise every component gives the
     * coordinates along its axis, counted from the base position.
     */
    private void place(List<IrValue> components, PlacedMolecule molecule, LayoutContext context, SourceInfo src)
            throws CompilationException {
        if (components.size() == 1 && "single".equals(kindOf(components.getFirst()))) {
            Value whole = context.evaluate(entry(components.getFirst(), "value"), src);
            if (whole instanceof Value.Vector) {
                context.placeObject(Nd.add(context.basePos(), Nd.coordinate(whole, src)), molecule, PLACED_BY, src);
                return;
            }
            if (whole instanceof Value.Position) {
                context.placeObject(Nd.coordinate(whole, src), molecule, PLACED_BY, src);
                return;
            }
        }
        List<Axis> axes = new ArrayList<>(components.size());
        for (int dimIndex = 0; dimIndex < components.size(); dimIndex++) {
            axes.add(axisOf(components.get(dimIndex), dimIndex, components.size(), context, src));
        }
        placeAlong(axes, 0, new int[axes.size()], molecule, context, src);
    }

    /**
     * Walks the axes from the first to the last, the first outermost, and places the molecule on
     * every cell the walk reaches. The counter is a {@code long}, so that a step beyond the
     * largest coordinate ends the walk instead of wrapping around.
     */
    private static void placeAlong(List<Axis> axes, int index, int[] coord, PlacedMolecule molecule,
                                   LayoutContext context, SourceInfo src) throws CompilationException {
        if (index == axes.size()) {
            context.placeObject(Nd.add(context.basePos(), coord), molecule, PLACED_BY, src);
            return;
        }
        Axis axis = axes.get(index);
        for (long value = axis.start(); value <= axis.end(); value += axis.step()) {
            coord[index] = (int) value;
            placeAlong(axes, index + 1, coord, molecule, context, src);
        }
    }

    /**
     * The coordinates one component gives along its axis: from a start to an end, both included,
     * in steps of a positive size.
     */
    private record Axis(int start, int end, int step) {
    }

    private static Axis axisOf(IrValue component, int dimIndex, int dimensions,
                               LayoutContext context, SourceInfo src) throws CompilationException {
        switch (kindOf(component)) {
            case "single" -> {
                int value = number(entry(component, "value"), context, src);
                return new Axis(value, value, 1);
            }
            case "range" -> {
                int start = number(entry(component, "start"), context, src);
                int end = number(entry(component, "end"), context, src);
                requireOrdered(start, end, src);
                return new Axis(start, end, 1);
            }
            case "stepped" -> {
                int start = number(entry(component, "start"), context, src);
                int step = number(entry(component, "step"), context, src);
                int end = number(entry(component, "end"), context, src);
                if (step < 1) {
                    throw new CompilationException(SourceInfo.locate(src, "Step " + step + " of a range must be positive."));
                }
                requireOrdered(start, end, src);
                return new Axis(start, end, step);
            }
            case "wildcard" -> {
                EnvironmentProperties envProps = context.getEnvProps();
                if (envProps == null || envProps.getWorldShape() == null) {
                    throw new CompilationException(SourceInfo.locate(src, "'*' in .PLACE needs a world shape."));
                }
                int[] shape = envProps.getWorldShape();
                if (dimIndex >= shape.length) {
                    throw new CompilationException(SourceInfo.locate(src, ".PLACE uses " + dimensions + " components, the world has " + shape.length + " dimensions."));
                }
                return new Axis(0, shape[dimIndex] - 1, 1);
            }
            default -> throw new IllegalStateException("Unknown placement component: " + kindOf(component));
        }
    }

    /**
     * A range counts upwards from its start to its end, so the end has to be at least the start.
     */
    private static void requireOrdered(int start, int end, SourceInfo src) throws CompilationException {
        if (end < start) {
            throw new CompilationException(SourceInfo.locate(src,
                    "End " + end + " of a range must be at least its start " + start + "."));
        }
    }

    /**
     * Computes a part of a component, which has to be a number, and turns it into a coordinate.
     */
    private static int number(IrValue part, LayoutContext context, SourceInfo src) throws CompilationException {
        if (!(context.evaluate(part, src) instanceof Value.Number number)) {
            throw new CompilationException(SourceInfo.locate(src, "Expected a number."));
        }
        return Nd.coordinate(List.of(number.value()), src)[0];
    }

    private static String kindOf(IrValue component) {
        return ((IrValue.Str) entry(component, "kind")).value();
    }

    private static IrValue entry(IrValue component, String key) {
        return ((IrValue.MapVal) component).entries().get(key);
    }
}
