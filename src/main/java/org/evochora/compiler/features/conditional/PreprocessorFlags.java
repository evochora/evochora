package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

import java.util.Map;
import java.util.OptionalLong;

/**
 * The flags of the preprocessor run, kept in the state slot of the preprocessor's context and
 * seeded from the configuration on the first request. The seeding reports every configured name
 * that is not an identifier, once per run, against the configuration rather than a source line;
 * the preprocessor owns that message, the Lexer phase reports nothing about flags.
 */
final class PreprocessorFlags {

    private PreprocessorFlags() {
    }

    /**
     * Returns the flags of the preprocessor run.
     *
     * @param preProcessor The preprocessor, for its diagnostics.
     * @param context      The context holding the slot and the options.
     * @return The one instance of the run.
     */
    static Flags in(PreProcessor preProcessor, PreProcessorContext context) {
        return context.getOrCreate(Flags.class, () -> {
            Map<String, OptionalLong> defines = context.options().defines();
            defines.keySet().stream().filter(name -> !Flags.isName(name)).sorted().forEach(name ->
                    preProcessor.getDiagnostics().reportError("Configured flag '" + name
                            + "' is not a valid name: a flag name consists of letters, digits and _"
                            + " and begins with a letter or _", "", 0));
            return Flags.of(defines);
        });
    }
}
