package org.evochora.compiler.frontend.lexer;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Context provided to {@link IDependencyScanHandler} implementations while the Lexer phase reads
 * the files of a compilation. Offers path resolution, reading a file as tokens, error reporting,
 * reading a file as a placement of its own, and the flags of the phase with the computation of a
 * number over them; a handler uses these to say what its directive means for the files, without
 * the phase knowing any directive.
 */
public interface IDependencyScanContext {

    /**
     * Resolves a path written in a directive against the file being read.
     * @param path The path as written.
     * @return The resolved absolute path.
     * @throws SourceRootResolver.UnresolvablePathException if the path prefix is unknown or the
     *                                                      file system does not allow the path.
     */
    String resolve(String path) throws SourceRootResolver.UnresolvablePathException;

    /**
     * Reports an error at the current line in the current file.
     * @param message The error message.
     */
    void reportError(String message);

    /**
     * Reads a module file as a placement of its own, nested in the placement being read, under
     * the given alias chain; a file imported more than once is read at every import. The reading
     * continues with the state of every feature as the nested one leaves it. A file that is
     * already being read on the way to this one is reported as a circular dependency. A file is
     * read and tokenized once per compilation, however often it is placed, and an error in it is
     * reported once, when it is read.
     *
     * @param resolvedPath The resolved absolute path.
     * @param aliasChain   The alias chain of the new placement.
     * @throws IOException if the file cannot be loaded.
     */
    void scanNestedModule(String resolvedPath, String aliasChain) throws IOException;

    /**
     * Returns the alias chain of the module placement being read. A source file read through
     * {@link #scanNestedSourceFile} belongs to the placement that includes it.
     *
     * @return The chain; empty for a main module compiled without a prefix.
     */
    String placementChain();

    /**
     * Reads a {@code .SOURCE} file in the placement being read, so that its directives are
     * followed and checked. The file is read and tokenized once per compilation.
     * @param resolvedPath The resolved absolute path.
     * @throws IOException if the file cannot be loaded.
     */
    void scanNestedSourceFile(String resolvedPath) throws IOException;

    /**
     * Reports a discovered dependency.
     * @param info The feature-specific dependency data.
     */
    void addDependency(IDependencyInfo info);

    /**
     * Returns the path of the file currently being read.
     * @return The path as it was resolved; errors reported through {@link #reportError}
     *         are attributed to this file.
     */
    String sourcePath();

    /**
     * Returns the current line number (1-based).
     * @return The number of the line last handed out in the current file: the line the handler
     *         was invoked for, or the line last taken through {@link #nextLine()}.
     */
    int lineNumber();

    /**
     * Takes the next statement line of the file being read: its tokens up to the statement end,
     * which a line break and a {@code ;} both are, the statement end itself left out, and an
     * empty line skipped. A line taken here is not offered to the handlers again; the reading
     * continues after it once the handler returns.
     *
     * @return The tokens of the next non-empty line, or {@code null} at the end of the file.
     */
    List<Token> nextLine();

    /**
     * The directive at the given index of the line last taken through {@link #nextLine()}, or of
     * the line the handler was invoked for, seen on the file's whole token stream: {@code before}
     * and {@code separator} find a statement next to the directive on the same physical line, and
     * {@code startsStatement} says whether the directive begins a statement.
     *
     * @param indexInLine The index of the directive token within the line's tokens.
     * @return The directive on its line.
     */
    DirectiveLine lineOf(int indexInLine);

    /**
     * The line of a directive once more, with the tokens directly before the directive that
     * belong to it passed over, so that {@code before} and {@code startsStatement} look past them:
     * the {@code EXPORT} an {@code .IMPORT} takes.
     *
     * @param line       The directive's line as the handler received it.
     * @param passedOver The tokens directly before the directive that belong to it.
     * @return The directive on its line, those tokens passed over.
     */
    DirectiveLine passingOver(DirectiveLine line, Predicate<Token> passedOver);

    /**
     * Returns the options of the compilation.
     * @return The options the phase was created with.
     */
    CompilerOptions options();

    /**
     * Returns the flags of the phase: one store for every file the phase reads, seeded from the
     * options of the compilation, which the directives of a feature fill as the lines are read.
     *
     * @return The flags of the phase.
     */
    Flags flags();

    /**
     * Computes a number at read time: the tokens of one value, a number, a flag name or a
     * computation, read with the operations of the compilation, every name a flag of
     * {@link #flags()} with its value. The phase reports nothing about it, as the preprocessor
     * reads the same tokens and reports what is wrong with them.
     *
     * @param tokens The tokens of the value, as the directive delimits them; not empty.
     * @return The number, or empty if the tokens form no computation or one without a number.
     */
    Optional<Long> evaluate(List<Token> tokens);

    /**
     * Returns the state object a feature keeps under the given key type, creating it with the
     * factory on the first request. One instance exists per key for the whole phase, across every
     * file it reads; the core knows nothing of the type and never reads the object.
     *
     * @param key     The class used as the key.
     * @param factory Creates the state object; called only while no object exists for the key.
     * @param <T>     The type of the state object.
     * @return The existing or newly created state object.
     */
    <T> T getOrCreate(Class<T> key, Supplier<T> factory);
}
