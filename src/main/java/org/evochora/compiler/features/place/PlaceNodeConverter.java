package org.evochora.compiler.features.place;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.features.place.placement.*;
import org.evochora.compiler.model.ir.IrDirective;
import org.evochora.compiler.model.ir.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link PlaceNode} into a generic {@link IrDirective} (namespace "core", name "place").
 * <p>
 * The directive carries the molecule as {@code value}, a literal, a qualified name or a
 * computation the layout finishes, and the placements as
 * {@code placements}: a list of placements, each a list of components, each component a map
 * whose {@code kind} names its form and whose other entries are its operands, each a literal, a
 * qualified name or a computation the layout finishes:
 * <ul>
 *   <li>{@code single}: {@code value};</li>
 *   <li>{@code range}: {@code start} and {@code end};</li>
 *   <li>{@code stepped}: {@code start}, {@code step} and {@code end};</li>
 *   <li>{@code wildcard}: nothing else.</li>
 * </ul>
 */
public final class PlaceNodeConverter implements IAstNodeToIrConverter<PlaceNode> {

    @Override
    public void convert(PlaceNode node, IrGenContext ctx) {
        Map<String, IrValue> args = new HashMap<>();
        args.put("value", ctx.convertValue(node.literal()));

        List<IrValue> placements = new ArrayList<>(node.placements().size());
        for (PlacementNode placement : node.placements()) {
            List<IrValue> components = new ArrayList<>(placement.components().size());
            for (IPlacementComponent component : placement.components()) {
                components.add(convertComponent(component, ctx));
            }
            placements.add(new IrValue.ListVal(List.copyOf(components)));
        }
        args.put("placements", new IrValue.ListVal(List.copyOf(placements)));

        ctx.emit(new IrDirective("core", "place", args, ctx.sourceOf(node)));
    }

    private static IrValue convertComponent(IPlacementComponent component, IrGenContext ctx) {
        Map<String, IrValue> entries = new HashMap<>();
        switch (component) {
            case SingleValueComponent single -> {
                entries.put("kind", new IrValue.Str("single"));
                entries.put("value", ctx.convertValue(single.value()));
            }
            case RangeValueComponent range -> {
                entries.put("kind", new IrValue.Str("range"));
                entries.put("start", ctx.convertValue(range.start()));
                entries.put("end", ctx.convertValue(range.end()));
            }
            case SteppedRangeValueComponent stepped -> {
                entries.put("kind", new IrValue.Str("stepped"));
                entries.put("start", ctx.convertValue(stepped.start()));
                entries.put("step", ctx.convertValue(stepped.step()));
                entries.put("end", ctx.convertValue(stepped.end()));
            }
            case WildcardValueComponent wildcard -> entries.put("kind", new IrValue.Str("wildcard"));
        }
        return new IrValue.MapVal(Map.copyOf(entries));
    }
}
