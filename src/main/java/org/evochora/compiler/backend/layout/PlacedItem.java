package org.evochora.compiler.backend.layout;

import org.evochora.compiler.model.ir.IrItem;

/**
 * An IR item together with the linear address the layout assigned to it.
 * <p>
 * Addresses are decided in exactly one place, and this is how that decision reaches the phases
 * that follow. A later phase that recomputed the address instead would have to reproduce how many
 * cells every kind of item occupies — a rule that would then exist twice and could drift apart.
 *
 * @param item The item as it entered the layout phase.
 * @param linearAddress The address of the item's first cell. An item that occupies no cell — a
 *                      directive, for instance — carries the address of the next cell to be
 *                      filled; directives move the position and direction within the world, never
 *                      the linear address, so that value is the same before and after one runs.
 */
public record PlacedItem(IrItem item, int linearAddress) {}
