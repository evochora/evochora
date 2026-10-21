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
            for (int[] coord : coordinatesOf(components, context, directive.source())) {
                context.placeObject(coord, molecule, "a .PLACE", directive.source());
            }
        }
    }

    /**
     * The molecule the directive places: its value computed at this line, a typed value whose
     * type the instruction set has to know and whose number has to fit a cell of that type.
     */
    private PlacedMolecule createMolecule(IrDirective directive, LayoutContext context) throws CompilationException {
        if (!(context.evaluate(directive.args().get("value"), directive.source()) instanceof Value.Typed typed)) {
            throw new CompilationException(SourceInfo.locate(directive.source(), "Expected a typed literal (e.g. DATA:5) for .PLACE."));
        }
        int type = isa.moleculeType(typed.type()).orElseThrow(() ->
                new CompilationException(SourceInfo.locate(directive.source(), "Unknown molecule type '" + typed.type() + "' in .PLACE.")));
        isa.valueRange(type).require(typed.number(), directive.source());
        return new PlacedMolecule(type, (int) typed.number(), 0);
    }

    /**
     * The cells of one placement, relative to the program origin. A placement of one single value
     * whose value is a vector is that place counted from the base position of the enclosing
     * module, one whose value is a position that place of the program; otherwise every component
     * gives the coordinates along its axis, counted from the base position.
     */
    private List<int[]> coordinatesOf(List<IrValue> components, LayoutContext context, SourceInfo src) throws CompilationException {
        if (components.size() == 1 && "single".equals(kindOf(components.getFirst()))) {
            Value whole = context.evaluate(entry(components.getFirst(), "value"), src);
            if (whole instanceof Value.Vector) {
                return List.of(Nd.add(context.basePos(), Nd.coordinate(whole, src)));
            }
            if (whole instanceof Value.Position) {
                return List.of(Nd.coordinate(whole, src));
            }
        }
        List<List<Integer>> dimensionValues = new ArrayList<>();
        for (int dimIndex = 0; dimIndex < components.size(); dimIndex++) {
            dimensionValues.add(getValuesForComponent(components.get(dimIndex), dimIndex, components.size(), context, src));
        }
        List<int[]> cells = new ArrayList<>();
        for (int[] coord : cartesianProduct(dimensionValues)) {
            cells.add(Nd.add(context.basePos(), coord));
        }
        return cells;
    }

    private List<Integer> getValuesForComponent(IrValue component, int dimIndex, int dimensions,
                                                LayoutContext context, SourceInfo src) throws CompilationException {
        switch (kindOf(component)) {
            case "single" -> {
                return List.of(number(entry(component, "value"), context, src));
            }
            case "range" -> {
                int start = number(entry(component, "start"), context, src);
                int end = number(entry(component, "end"), context, src);
                List<Integer> values = new ArrayList<>();
                for (int i = start; i <= end; i++) {
                    values.add(i);
                }
                return values;
            }
            case "stepped" -> {
                int start = number(entry(component, "start"), context, src);
                int step = number(entry(component, "step"), context, src);
                int end = number(entry(component, "end"), context, src);
                List<Integer> values = new ArrayList<>();
                for (int i = start; i <= end; i += step) {
                    values.add(i);
                }
                return values;
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
                List<Integer> values = new ArrayList<>();
                for (int i = 0; i < shape[dimIndex]; i++) {
                    values.add(i);
                }
                return values;
            }
            default -> throw new IllegalStateException("Unknown placement component: " + kindOf(component));
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

    private List<int[]> cartesianProduct(List<List<Integer>> lists) {
        List<int[]> result = new ArrayList<>();
        if (lists.isEmpty()) {
            return result;
        }
        List<Integer> firstList = lists.get(0);
        if (lists.size() == 1) {
            for (Integer i : firstList) {
                result.add(new int[]{i});
            }
            return result;
        }
        List<List<Integer>> remainingLists = lists.subList(1, lists.size());
        List<int[]> subProduct = cartesianProduct(remainingLists);
        for (Integer i : firstList) {
            for (int[] p : subProduct) {
                int[] newP = new int[p.length + 1];
                newP[0] = i;
                System.arraycopy(p, 0, newP, 1, p.length);
                result.add(newP);
            }
        }
        return result;
    }

}
