package org.evochora.compiler.backend.layout;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.SourceInfo;

import java.util.Map;

/**
 * Result of the layout phase (without linking): holds coordinates and mappings.
 *
 * @param linearAddressToCoord A map from linear address to relative coordinates.
 * @param relativeCoordToLinearAddress A map from relative coordinate string to linear address.
 * @param labelToAddress A map from label names to their linear addresses.
 * @param sourceMap A map from linear address to source information.
 * @param initialWorldObjects A map from relative coordinates to molecules that should be placed in the world initially.
 */
public record LayoutResult(
        Map<Integer, int[]> linearAddressToCoord,
        Map<String, Integer> relativeCoordToLinearAddress,
        Map<String, Integer> labelToAddress,
        Map<Integer, SourceInfo> sourceMap,
        Map<int[], PlacedMolecule> initialWorldObjects
) {}
