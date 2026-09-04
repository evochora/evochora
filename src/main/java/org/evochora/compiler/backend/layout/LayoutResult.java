package org.evochora.compiler.backend.layout;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;

import java.util.List;
import java.util.Map;

/**
 * Result of the layout phase (without linking): holds coordinates and mappings.
 *
 * @param linearAddressToCoord A map from linear address to relative coordinates.
 * @param relativeCoordToLinearAddress A map from relative coordinate string to linear address.
 * @param labelToAddress A map from label names to their linear addresses.
 * @param labelToValue A map from label names to the values that stand for them in the machine
 *                     code, one value per label: the layout assigns them so that no two labels
 *                     of a program share one.
 * @param sourceMap A map from linear address to source information.
 * @param initialWorldObjects A map from relative coordinates to molecules that should be placed in the world initially.
 * @param placedItems The items in the order the layout walked them, each with the address it was given.
 */
public record LayoutResult(
        Map<Integer, int[]> linearAddressToCoord,
        Map<String, Integer> relativeCoordToLinearAddress,
        Map<String, Integer> labelToAddress,
        Map<String, Integer> labelToValue,
        Map<Integer, SourceInfo> sourceMap,
        Map<int[], PlacedMolecule> initialWorldObjects,
        List<PlacedItem> placedItems
) {}
