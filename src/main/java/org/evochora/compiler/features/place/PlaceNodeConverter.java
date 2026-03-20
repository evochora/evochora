package org.evochora.compiler.features.place;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.model.ast.NumberLiteralNode;
import org.evochora.compiler.model.ast.TypedLiteralNode;
import org.evochora.compiler.features.place.placement.*;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.compiler.model.ir.placement.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts {@link PlaceNode} into a generic {@link IrDirective} (namespace "core", name "place").
 */
public final class PlaceNodeConverter implements IAstNodeToIrConverter<PlaceNode> {

    @Override
    public void convert(PlaceNode node, IrGenContext ctx) {
        Map<String, IrValue> args = new HashMap<>();

        // Convert the literal part
        if (node.literal() instanceof TypedLiteralNode t) {
            args.put("type", new IrValue.Str(t.typeName()));
            args.put("value", new IrValue.Int64(t.value()));
        } else if (node.literal() instanceof NumberLiteralNode n) {
            args.put("value", new IrValue.Int64(n.value()));
        }

        // Convert the placements part
        List<IPlacementArgument> irPlacements = new ArrayList<>();
        for (IPlacementArgumentNode placementNode : node.placements()) {
            irPlacements.add(convertPlacementArgument(placementNode));
        }
        args.put("placements", new IrValue.PlacementListVal(irPlacements));

        ctx.emit(new IrDirective("core", "place", args, ctx.sourceOf(node)));
    }

    private IPlacementArgument convertPlacementArgument(IPlacementArgumentNode node) {
        if (node instanceof VectorPlacementNode vpn) {
            List<Integer> components = vpn.vector().values();
            return new IrVectorPlacement(components);
        } else if (node instanceof RangeExpressionNode ren) {
            List<List<IIrPlacementComponent>> irDimensions = new ArrayList<>();
            for (List<IPlacementComponent> dim : ren.dimensions()) {
                irDimensions.add(dim.stream()
                        .map(this::convertPlacementComponent)
                        .collect(Collectors.toList()));
            }
            return new IrRangeExpression(irDimensions);
        }
        // Should not happen if parser is correct
        throw new IllegalStateException("Unknown IPlacementArgumentNode type: " + node.getClass().getName());
    }

    private IIrPlacementComponent convertPlacementComponent(IPlacementComponent component) {
        if (component instanceof SingleValueComponent svc) {
            return new IrSingleValueComponent(svc.value());
        } else if (component instanceof RangeValueComponent rvc) {
            return new IrRangeValueComponent(rvc.start(), rvc.end());
        } else if (component instanceof SteppedRangeValueComponent srvc) {
            return new IrSteppedRangeValueComponent(srvc.start(), srvc.step(), srvc.end());
        } else if (component instanceof WildcardValueComponent) {
            return new IrWildcardValueComponent();
        }
        throw new IllegalStateException("Unknown IPlacementComponent type: " + component.getClass().getName());
    }
}
