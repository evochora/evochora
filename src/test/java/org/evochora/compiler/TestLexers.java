package org.evochora.compiler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Tokenizer;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;

import java.util.Set;

/**
 * Supplies the lexer symbols of the standard features to tests that build a
 * {@link org.evochora.compiler.frontend.lexer.Tokenizer} directly, mirroring what {@link Compiler}
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

    /**
     * Builds a tokenizer for one source text as the compiler builds one: with the runtime's
     * instruction set and the symbols of the standard features.
     *
     * @param source      The source text.
     * @param diagnostics The engine errors are reported to.
     * @return The tokenizer, naming the file {@code <memory>}.
     */
    public static Tokenizer tokenizer(String source, DiagnosticsEngine diagnostics) {
        return tokenizer(source, diagnostics, "<memory>");
    }

    /**
     * Builds a tokenizer for one source text under a file name.
     *
     * @param source      The source text.
     * @param diagnostics The engine errors are reported to.
     * @param fileName    The logical file name the tokens carry.
     * @return The tokenizer.
     */
    public static Tokenizer tokenizer(String source, DiagnosticsEngine diagnostics, String fileName) {
        return new Tokenizer(source, diagnostics, fileName, new RuntimeInstructionSetAdapter(), STANDARD_SYMBOLS);
    }

    private static Set<String> collectStandardSymbols() {
        FeatureRegistry featureRegistry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));
        return Set.copyOf(featureRegistry.lexerSymbols());
    }
}
