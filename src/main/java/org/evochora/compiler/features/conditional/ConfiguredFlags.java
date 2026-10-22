package org.evochora.compiler.features.conditional;

import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;

/**
 * The check of the configured flags in the preprocessor run: every configured name that is not
 * an identifier, or that is reserved as a {@code .DEFINE} of it would be, is reported once per run, against the configuration rather than a source line,
 * when the first conditional directive touches the flags. The preprocessor owns that message; the
 * Lexer phase reports nothing about flags.
 */
final class ConfiguredFlags {

    private ConfiguredFlags() {
    }

    /**
     * Reports the configured names that are no flag names or are reserved, on the first call of
     * the run.
     *
     * @param preProcessor The preprocessor, for its diagnostics.
     * @param context      The context holding the options and the state slot that marks the
     *                     check as done.
     */
    static void check(PreProcessor preProcessor, PreProcessorContext context) {
        context.getOrCreate(ConfiguredFlags.class, () -> {
            context.options().defines().keySet().stream().sorted().forEach(name -> {
                if (!Flags.isName(name)) {
                    preProcessor.getDiagnostics().reportError("Configured flag '" + name
                            + "' is not a valid name: a flag name consists of letters, digits and _"
                            + " and begins with a letter or _", "", 0);
                    return;
                }
                context.reservedName(name).ifPresent(reserved -> preProcessor.getDiagnostics().reportError(
                        "Configured flag '" + name + "' is " + reserved + " and cannot be defined.", "", 0));
            });
            return new ConfiguredFlags();
        });
    }
}
