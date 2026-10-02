package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * What a program carries for a debugger and a source view besides its items: the text of its
 * files, the classification of its tokens and the preprocessor flags it was compiled with. No
 * phase of the backend reads or changes it; the emitter copies it into the artifact and puts the
 * flags into the program's identity.
 *
 * @param sources  The text of every file once per module placement it stands in, with the
 *                 regions and notes the preprocessor recorded for it.
 * @param tokenMap The classification of every token by its source position.
 * @param flags    The preprocessor flags of the compilation as its options normalise them: each
 *                 name upper-cased, each value the flag's integer value or empty for a flag set
 *                 without one. The regions and notes of the sources were recorded under them.
 */
public record DebugInfo(List<SourceFile> sources, Map<SourceInfo, TokenInfo> tokenMap,
                        Map<String, OptionalInt> flags) {

    /**
     * Makes the record immutable by copying the list and the maps; the copy of the token map
     * keeps the iteration order of the given one.
     */
    public DebugInfo {
        sources = List.copyOf(sources);
        tokenMap = Collections.unmodifiableMap(new LinkedHashMap<>(tokenMap));
        flags = Map.copyOf(flags);
    }

    /**
     * Returns the debug information of a program without files, tokens or flags.
     *
     * @return An empty instance.
     */
    public static DebugInfo none() {
        return new DebugInfo(List.of(), Map.of(), Map.of());
    }
}
