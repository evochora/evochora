package org.evochora.compiler.features.place;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.compiler.model.ir.placement.*;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Layout handler for the {@code core:place} IR directive (Phase 9). Evaluates
 * placement arguments (vectors, ranges, wildcards) and writes the resulting
 * molecules into the initial world object map at the computed coordinates.
 */
public final class PlaceLayoutHandler implements ILayoutDirectiveHandler {

    private final IInstructionSet isa;

    /**
     * @param isa The instruction set, which names the molecule types a placement may use.
     */
    public PlaceLayoutHandler(IInstructionSet isa) {
        this.isa = isa;
    }

    @Override
    public void handle(IrDirective directive, LayoutContext context) throws CompilationException {
        IrValue.PlacementListVal placementsVal = (IrValue.PlacementListVal) directive.args().get("placements");
        if (placementsVal == null) {
            // Fallback for old syntax, though IR converter should prevent this.
            return;
        }

        PlacedMolecule molecule = createMolecule(directive);
        List<IPlacementArgument> placements = placementsVal.placements();

        for (IPlacementArgument placement : placements) {
            List<int[]> coordinates = generateCoordinates(placement, context, directive.source());
            for (int[] coord : coordinates) {
                int[] finalCoord = Nd.add(context.basePos(), coord);
                context.placeObject(finalCoord, molecule, "a .PLACE", directive.source());
            }
        }
    }

    private PlacedMolecule createMolecule(IrDirective directive) throws CompilationException {
        IrValue val = directive.args().get("value");
        IrValue.Str t = (IrValue.Str) directive.args().get("type");
        if (t == null) {
            throw new IllegalStateException(
                    "Missing 'type' argument in place directive — PlaceNodeConverter must provide it");
        }
        String ts = t.value();
        int type = isa.moleculeType(ts).orElseThrow(() ->
                new CompilationException(located(directive.source(), "Unknown molecule type '" + ts + "' in .PLACE.")));
        long value = val instanceof IrValue.Int64 iv ? iv.value() : 0L;
        return new PlacedMolecule(type, (int) value, 0);
    }

    private List<int[]> generateCoordinates(IPlacementArgument placement, LayoutContext context, SourceInfo src) throws CompilationException {
        if (placement instanceof IrVectorPlacement vp) {
            return List.of(vp.components().stream().mapToInt(i -> i).toArray());
        } else if (placement instanceof IrRangeExpression re) {
            List<List<Integer>> dimensionValues = new ArrayList<>();
            int dimIndex = 0;
            for (List<IIrPlacementComponent> dimComponents : re.dimensions()) {
                // For now, we only support one component per dimension
                if (dimComponents.size() != 1) {
                    throw new CompilationException(located(src, ".PLACE has " + dimComponents.size()
                            + " components in dimension " + (dimIndex + 1) + "; only one is supported."));
                }
                IIrPlacementComponent component = dimComponents.get(0);
                dimensionValues.add(getValuesForComponent(component, dimIndex++, re.dimensions().size(), context, src));
            }
            return cartesianProduct(dimensionValues);
        }
        return Collections.emptyList();
    }

    private List<Integer> getValuesForComponent(IIrPlacementComponent component, int dimIndex, int dimensions,
                                                LayoutContext context, SourceInfo src) throws CompilationException {
        if (component instanceof IrSingleValueComponent svc) {
            return List.of(svc.value());
        } else if (component instanceof IrRangeValueComponent rvc) {
            List<Integer> values = new ArrayList<>();
            for (int i = rvc.start(); i <= rvc.end(); i++) {
                values.add(i);
            }
            return values;
        } else if (component instanceof IrSteppedRangeValueComponent srvc) {
            List<Integer> values = new ArrayList<>();
            for (int i = srvc.start(); i <= srvc.end(); i += srvc.step()) {
                values.add(i);
            }
            return values;
        } else if (component instanceof IrWildcardValueComponent) {
            EnvironmentProperties envProps = context.getEnvProps();
            if (envProps == null || envProps.getWorldShape() == null) {
                throw new CompilationException(located(src, "'*' in .PLACE needs a world shape."));
            }
            int[] shape = envProps.getWorldShape();
            if (dimIndex >= shape.length) {
                throw new CompilationException(located(src, ".PLACE uses " + dimensions + " dimensions, the world has " + shape.length + "."));
            }
            List<Integer> values = new ArrayList<>();
            for (int i = 0; i < shape[dimIndex]; i++) {
                values.add(i);
            }
            return values;
        }
        return Collections.emptyList();
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

    /**
     * Prefixes a message with the file and line of the directive, as every backend message is.
     */
    private static String located(SourceInfo src, String message) {
        if (src == null) return message;
        return String.format("%s:%d: %s", src.fileName() != null ? src.fileName() : "<unknown>", src.lineNumber(), message);
    }
}
