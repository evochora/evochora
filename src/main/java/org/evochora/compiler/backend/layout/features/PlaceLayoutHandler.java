package org.evochora.compiler.backend.layout.features;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.layout.LayoutContext;
import org.evochora.compiler.backend.layout.Nd;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;
import org.evochora.compiler.ir.placement.*;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.MoleculeTypeRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlaceLayoutHandler implements ILayoutDirectiveHandler {

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
            List<int[]> coordinates = generateCoordinates(placement, context);
            for (int[] coord : coordinates) {
                int[] finalCoord = Nd.add(context.basePos(), coord);
                context.initialWorldObjects().put(finalCoord, molecule);
            }
        }
    }

    private PlacedMolecule createMolecule(IrDirective directive) throws CompilationException {
        IrValue val = directive.args().get("value");
        IrValue.Str t = (IrValue.Str) directive.args().get("type");
        String ts = t != null ? t.value() : "DATA";
        int type;
        try {
            type = MoleculeTypeRegistry.nameToType(ts);
        } catch (IllegalArgumentException e) {
            throw new CompilationException("Unknown molecule type in .PLACE directive: " + ts + ". " + e.getMessage());
        }
        long value = val instanceof IrValue.Int64 iv ? iv.value() : 0L;
        return new PlacedMolecule(type, (int) value);
    }

    private List<int[]> generateCoordinates(IPlacementArgument placement, LayoutContext context) throws CompilationException {
        if (placement instanceof IrVectorPlacement vp) {
            return List.of(vp.components().stream().mapToInt(i -> i).toArray());
        } else if (placement instanceof IrRangeExpression re) {
            List<List<Integer>> dimensionValues = new ArrayList<>();
            int dimIndex = 0;
            for (List<IIrPlacementComponent> dimComponents : re.dimensions()) {
                // For now, we only support one component per dimension
                if (dimComponents.size() != 1) {
                    throw new CompilationException("Multiple components per dimension are not yet supported.");
                }
                IIrPlacementComponent component = dimComponents.get(0);
                dimensionValues.add(getValuesForComponent(component, dimIndex++, context));
            }
            return cartesianProduct(dimensionValues);
        }
        return Collections.emptyList();
    }

    private List<Integer> getValuesForComponent(IIrPlacementComponent component, int dimIndex, LayoutContext context) throws CompilationException {
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
                throw new CompilationException("Use of '*' in .PLACE requires a compilation context with world dimensions.");
            }
            int[] shape = envProps.getWorldShape();
            if (dimIndex >= shape.length) {
                throw new CompilationException("Wildcard used for dimension " + dimIndex + " which is out of bounds for the world shape.");
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
}
