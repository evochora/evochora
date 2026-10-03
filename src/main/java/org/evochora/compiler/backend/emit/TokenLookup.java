package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the artifact's index of the token map by position: placement, file name, line and
 * column. Two placements of one file share its positions, so the placement comes first.
 */
final class TokenLookup {

    private TokenLookup() {
    }

    /**
     * Builds the four-level lookup: placement, then file name, then line, then column, to the
     * tokens at that position.
     *
     * @param tokenMap The flat token map keyed by {@link SourceInfo}.
     * @return A nested lookup map suitable for debuggers and indexers.
     */
    static Map<String, Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>>> of(
            Map<SourceInfo, TokenInfo> tokenMap) {
        Map<String, Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>>> result = new HashMap<>();

        for (Map.Entry<SourceInfo, TokenInfo> entry : tokenMap.entrySet()) {
            SourceInfo sourceInfo = entry.getKey();
            result.computeIfAbsent(sourceInfo.placement(), k -> new HashMap<>())
                  .computeIfAbsent(sourceInfo.fileName(), k -> new HashMap<>())
                  .computeIfAbsent(sourceInfo.lineNumber(), k -> new HashMap<>())
                  .computeIfAbsent(sourceInfo.columnNumber(), k -> new ArrayList<>())
                  .add(entry.getValue());
        }

        return result;
    }
}
