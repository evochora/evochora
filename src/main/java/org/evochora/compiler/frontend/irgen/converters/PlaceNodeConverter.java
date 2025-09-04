package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.ast.NumberLiteralNode;
import org.evochora.compiler.frontend.parser.ast.TypedLiteralNode;
import org.evochora.compiler.frontend.parser.ast.placement.*;
import org.evochora.compiler.frontend.parser.features.place.PlaceNode;
import org.evochora.compiler.ir.IrDirective;
import org.evochora.compiler.ir.IrValue;
import org.evochora.compiler.ir.placement.*;

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
            args.put("type", new IrValue.Str(t.type().text()));
            args.put("value", new IrValue.Int64(Long.parseLong(t.value().text())));
        } else if (node.literal() instanceof NumberLiteralNode n) {
            args.put("value", new IrValue.Int64(n.getValue()));
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
            int[] comps = vpn.vector().components().stream().mapToInt(tok -> Integer.parseInt(tok.text())).toArray();
            List<Integer> components = new ArrayList<>();
            for (int comp : comps) {
                components.add(comp);
            }
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
            return new IrSingleValueComponent(Integer.parseInt(svc.value().text()));
        } else if (component instanceof RangeValueComponent rvc) {
            int start = Integer.parseInt(rvc.start().text());
            int end = Integer.parseInt(rvc.end().text());
            return new IrRangeValueComponent(start, end);
        } else if (component instanceof SteppedRangeValueComponent srvc) {
            int start = Integer.parseInt(srvc.start().text());
            int step = Integer.parseInt(srvc.step().text());
            int end = Integer.parseInt(srvc.end().text());
            return new IrSteppedRangeValueComponent(start, step, end);
        } else if (component instanceof WildcardValueComponent) {
            return new IrWildcardValueComponent();
        }
        // Should not happen
        throw new IllegalStateException("Unknown IPlacementComponent type: " + component.getClass().getName());
    }
}
