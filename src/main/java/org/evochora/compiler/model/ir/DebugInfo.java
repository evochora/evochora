package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a program carries for a debugger and a source view besides its items: the text of its
 * files and the classification of its tokens. No phase of the backend reads or changes it; the
 * emitter copies it into the artifact.
 *
 * @param sources  The text of every file once per module placement it stands in, with the
 *                 regions and notes the preprocessor recorded for it.
 * @param tokenMap The classification of every token by its source position.
 */
public record DebugInfo(List<SourceFile> sources, Map<SourceInfo, TokenInfo> tokenMap) {

    /**
     * Makes the record immutable by copying the list and the map; the copy of the map keeps
     * the iteration order of the given one.
     */
    public DebugInfo {
        sources = List.copyOf(sources);
        tokenMap = Collections.unmodifiableMap(new LinkedHashMap<>(tokenMap));
    }

    /**
     * Returns the debug information of a program without files or tokens.
     *
     * @return An empty instance.
     */
    public static DebugInfo none() {
        return new DebugInfo(List.of(), Map.of());
    }
}
