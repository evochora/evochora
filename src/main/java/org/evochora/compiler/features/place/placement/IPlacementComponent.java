package org.evochora.compiler.features.place.placement;

import org.evochora.compiler.model.ast.AstNode;

/**
 * A component of a single dimension in a .PLACE directive: a single value, a range, a stepped
 * range or a wildcard. Its operands are its children, so that the phases that walk the tree reach
 * every computation in it.
 */
public sealed interface IPlacementComponent extends AstNode
        permits SingleValueComponent, RangeValueComponent, SteppedRangeValueComponent, WildcardValueComponent {
}
