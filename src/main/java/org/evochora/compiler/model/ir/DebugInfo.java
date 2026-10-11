package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.Expansion;
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
 * files, the classification of its tokens and the defines and values of the compilation's
 * options. No phase of the backend reads or changes it; the emitter copies it into the artifact
 * and puts the defines and values into the program's identity.
 *
 * @param sources  One entry per inclusion of a file, with the regions and notes the
 *                 preprocessor recorded for it.
 * @param expansions Every instance of injected tokens that is no inclusion, by its number, with
 *                 where it came from and the regions and notes recorded in it.
 * @param tokenMap The classification of every token by its source position.
 * @param flags    The defines of the compilation's options as the options normalise them: each
 *                 name upper-cased, each value an integer or empty for a name defined without
 *                 one. The regions and notes of the sources were recorded under them.
 * @param values   The values of the compilation's options as the options normalise them: each
 *                 name upper-cased, each value the text of an operand.
 */
public record DebugInfo(List<SourceFile> sources, Map<Integer, Expansion> expansions,
                        Map<SourceInfo, TokenInfo> tokenMap,
                        Map<String, OptionalInt> flags, Map<String, String> values) {

    /**
     * Makes the record immutable by copying the list and the maps; the copy of the token map
     * keeps the iteration order of the given one.
     */
    public DebugInfo {
        sources = List.copyOf(sources);
        expansions = Map.copyOf(expansions);
        tokenMap = Collections.unmodifiableMap(new LinkedHashMap<>(tokenMap));
        flags = Map.copyOf(flags);
        values = Map.copyOf(values);
    }

    /**
     * Returns the debug information of a program without files, instances, tokens, defines or
     * values.
     *
     * @return An empty instance.
     */
    public static DebugInfo none() {
        return new DebugInfo(List.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
