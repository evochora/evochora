package org.evochora.compiler;

import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;

import java.util.Set;

/**
 * Supplies the lexer symbols of the standard features to tests that build a
 * {@link org.evochora.compiler.frontend.lexer.Lexer} directly, mirroring what {@link Compiler}
 * passes. Uses {@link StandardFeatures#all()} as the feature source.
 */
public final class TestLexers {

    private static final Set<String> STANDARD_SYMBOLS = collectStandardSymbols();

    private TestLexers() {}

    /**
     * Returns the symbols the standard features register with the lexer.
     *
     * @return An immutable set of the registered character sequences.
     */
    public static Set<String> symbols() {
        return STANDARD_SYMBOLS;
    }

    private static Set<String> collectStandardSymbols() {
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));
        return Set.copyOf(featureRegistry.lexerSymbols());
    }
}
