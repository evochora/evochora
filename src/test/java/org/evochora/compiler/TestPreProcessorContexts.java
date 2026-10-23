package org.evochora.compiler;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds preprocessor contexts for tests that run the preprocessor phase directly, which know no
 * operation of computations: a value read at this time is a number or a flag, and an operator in
 * it is unexpected. The instruction set is the runtime's, the flags are seeded from the options.
 */
public final class TestPreProcessorContexts {

    private static final OperationRegistry NO_OPERATIONS = new OperationRegistry();

    private TestPreProcessorContexts() {}

    /**
     * Builds a context carrying the token streams that were pre-lexed and the lines that were
     * read for the files found during dependency scanning, which knows no operation of
     * computations. A null alias chain becomes the empty chain, a null map an empty map.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param fileTokens     Pre-lexed tokens of every file that may be included, keyed by
     *                       resolved absolute path.
     * @param fileLines      The lines of every file read, the main file's included, keyed by
     *                       resolved absolute path.
     * @param mainName       The name the main file was given to the compiler under.
     * @param mainPath       The resolved path of the main file; must not be null.
     * @param options        The options of the compilation, whose flags seed the context's
     *                       flags; must not be null.
     * @param diagnostics    Where the context reports an error of a computation it reads.
     * @return The context, with no operations.
     */
    public static PreProcessorContext context(String rootAliasChain, Map<String, List<Token>> fileTokens,
                                              Map<String, List<String>> fileLines, String mainName,
                                              String mainPath, CompilerOptions options, DiagnosticsEngine diagnostics) {
        return new PreProcessorContext(rootAliasChain, fileTokens, fileLines, mainName, mainPath, options,
                NO_OPERATIONS, new RuntimeInstructionSetAdapter(),
                Flags.of(Objects.requireNonNull(options, "options").defines(), NO_OPERATIONS.reservedNames()), diagnostics);
    }

    /**
     * Builds a context carrying pre-lexed token streams but no lines: the main file's entry is
     * named by its path and shows no text.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param fileTokens     Pre-lexed tokens of every file that may be included, keyed by
     *                       resolved absolute path.
     * @param mainPath       The resolved path of the main file, the file name of the initial
     *                       tokens; must not be null.
     * @param options        The options of the compilation; must not be null.
     * @param diagnostics    Where the context reports an error of a computation it reads.
     * @return The context, with no operations.
     */
    public static PreProcessorContext context(String rootAliasChain, Map<String, List<Token>> fileTokens,
                                              String mainPath, CompilerOptions options, DiagnosticsEngine diagnostics) {
        return context(rootAliasChain, fileTokens, Map.of(), mainPath, mainPath, options, diagnostics);
    }
}
