package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.api.PositionedNote;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The output of Phase 5 (token map generation): the classification of every token, and the
 * notes that show the value of every computation phase 4 computed behind the expression it
 * belongs to.
 *
 * @param tokenMap The classification of every token by its position, with instance 0.
 * @param notes    The notes {@code [=value]} of each computation that has a value, each at the end
 *                 of the expression it belongs to, each distinct note once, in the order the walk
 *                 met them.
 */
public record TokenMapResult(Map<SourceInfo, TokenInfo> tokenMap, List<PositionedNote> notes) {

    /**
     * Makes the record immutable by copying the map, in its iteration order, and the list.
     */
    public TokenMapResult {
        tokenMap = Collections.unmodifiableMap(new LinkedHashMap<>(tokenMap));
        notes = List.copyOf(notes);
    }
}
