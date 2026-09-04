package org.evochora.compiler.model.symbols;

import java.util.Optional;

/**
 * What a name lookup came to: the symbol with its qualified name, or the reason there is none.
 * The reason is worded where the lookup stopped, so that a phase reporting the failure can say
 * why without walking the modules again.
 */
public sealed interface Resolution permits ResolvedSymbol, Resolution.Missing {

    /**
     * A lookup that found nothing.
     *
     * @param explanation Why, as a fragment that completes a sentence of the form
     *                    {@code Cannot <do something with> 'X': …}; it starts in lowercase or
     *                    with a module name and ends with a period, e.g.
     *                    {@code "import 'ARITH' of NAV is not marked EXPORT."}
     */
    record Missing(String explanation) implements Resolution {
    }

    /**
     * Views this resolution as an optional symbol, for callers that only need to know whether the
     * lookup succeeded.
     *
     * @return The symbol found, or empty if the lookup found nothing.
     */
    default Optional<ResolvedSymbol> found() {
        return this instanceof ResolvedSymbol resolved ? Optional.of(resolved) : Optional.empty();
    }
}
