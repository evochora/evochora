package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.frontend.Flags;
import org.evochora.compiler.frontend.ReadTimeValues;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.token.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A shared context for the preprocessor phase.
 * Contains the state that handlers read and modify while the token stream is expanded: the
 * handlers the preprocessor dispatches to, the pre-lexed token streams and the lines of the files
 * that may be included, the entry of the main file, the inclusions currently open, the options of the compilation, the numbers of the instances of injected
 * tokens, the names nothing may define, the flags with the computation of a number over them, and
 * a slot in which features keep state of their own types.
 */
public class PreProcessorContext {
    private static final OperationRegistry NO_OPERATIONS = new OperationRegistry();

    private final PreProcessorHandlerRegistry handlers = new PreProcessorHandlerRegistry();
    private final String rootAliasChain;
    private final Deque<PlacementContext> inclusions = new ArrayDeque<>();
    private final Map<String, List<Token>> fileTokens;
    private final Map<String, List<String>> fileLines;
    private final SourceFile mainFile;
    private final CompilerOptions options;
    private final Map<String, String> reservedNames;
    private final ReadTimeValues values;
    private DiagnosticsEngine diagnostics;
    private BiConsumer<SourceInfo, String> notes;
    private final Map<Class<?>, Object> featureState = new HashMap<>();
    private int lastInstance;

    /**
     * Creates a context carrying the token streams that were pre-lexed and the lines that were
     * read for the files found during dependency scanning, which knows no operation of
     * computations: a value read at this time is a number or a flag, and an operator in it is
     * unexpected. A null alias chain becomes the empty chain, a null map an empty map.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param fileTokens     Pre-lexed tokens of every file that may be included, keyed by
     *                       resolved absolute path. Whether an inclusion is a module or plain
     *                       text is decided by the directive that includes the file, not here.
     * @param fileLines      The lines of every file read, the main file's included, keyed by
     *                       resolved absolute path; the entries of one file share its list.
     * @param mainName       The name the main file was given to the compiler under, which names
     *                       its entry where the program's text is shown.
     * @param mainPath       The resolved path of the main file, the file name of the initial
     *                       tokens; the main file's entry, the first of the preprocessor's
     *                       entries, stands under the root chain with the lines of this path.
     *                       Must not be null.
     * @param options        The options of the compilation, offered to handlers through
     *                       {@link #options()}, whose flags seed {@link #flags()}; must not be
     *                       null.
     */
    public PreProcessorContext(String rootAliasChain, Map<String, List<Token>> fileTokens,
                               Map<String, List<String>> fileLines, String mainName, String mainPath,
                               CompilerOptions options) {
        this(rootAliasChain, fileTokens, fileLines, mainName, mainPath, options, NO_OPERATIONS,
                new RuntimeInstructionSetAdapter(),
                Flags.of(Objects.requireNonNull(options, "options").defines(), NO_OPERATIONS.reservedNames()));
    }

    /**
     * Creates a context as {@link #PreProcessorContext(String, Map, Map, String, String, CompilerOptions)}
     * does, which computes with the operations of the compilation and offers the names nothing in
     * a program may define.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param fileTokens     Pre-lexed tokens of every file that may be included, keyed by
     *                       resolved absolute path.
     * @param fileLines      The lines of every file read, keyed by resolved absolute path.
     * @param mainName       The name the main file was given to the compiler under.
     * @param mainPath       The resolved path of the main file; must not be null.
     * @param options        The options of the compilation; must not be null.
     * @param operations     The operations of the compilation, with which {@link #evaluate}
     *                       computes; their names, and the cursor {@code @}, are the names
     *                       nothing may define.
     * @param isa            The instruction set, whose molecule types a computation recognises
     *                       before the typing colon.
     * @param flags          The flags of the phase, seeded from the options.
     */
    public PreProcessorContext(String rootAliasChain, Map<String, List<Token>> fileTokens,
                               Map<String, List<String>> fileLines, String mainName, String mainPath,
                               CompilerOptions options, OperationRegistry operations, IInstructionSet isa,
                               Flags flags) {
        this.rootAliasChain = rootAliasChain != null ? rootAliasChain : "";
        this.fileTokens = fileTokens != null ? fileTokens : Map.of();
        this.fileLines = fileLines != null ? fileLines : Map.of();
        this.mainFile = new SourceFile(this.rootAliasChain, mainName,
                Objects.requireNonNull(mainPath, "mainPath"), linesOf(mainPath));
        this.options = Objects.requireNonNull(options, "options");
        Map<String, String> reserved = new HashMap<>();
        operations.reservedNames().forEach((name, what) -> reserved.put(name.toUpperCase(Locale.ROOT), what));
        this.reservedNames = Map.copyOf(reserved);
        this.values = new ReadTimeValues(flags, operations, isa);
    }

    /**
     * Returns what a name is if nothing in a program may define it, compared without regard to
     * letter case, so that a handler that defines a name, as {@code .MACRO} does, can reject it.
     *
     * @param name The name, in any letter case.
     * @return What the name is, "an operator" or "a function", or the empty text for a name
     *         reserved as it is; empty if the name is not reserved.
     */
    public Optional<String> reservedName(String name) {
        return Optional.ofNullable(reservedNames.get(name.toUpperCase(Locale.ROOT)));
    }

    /**
     * Creates a context carrying pre-lexed token streams but no lines: the main file's entry is
     * named by its path and shows no text.
     *
     * @param rootAliasChain The alias chain for the compilation root module.
     * @param fileTokens     Pre-lexed tokens of every file that may be included, keyed by
     *                       resolved absolute path.
     * @param mainPath       The resolved path of the main file, the file name of the initial
     *                       tokens; must not be null.
     * @param options        The options of the compilation; must not be null.
     */
    public PreProcessorContext(String rootAliasChain, Map<String, List<Token>> fileTokens, String mainPath,
                               CompilerOptions options) {
        this(rootAliasChain, fileTokens, Map.of(), mainPath, mainPath, options);
    }

    /**
     * Returns the handlers the preprocessor dispatches to. The compiler fills the registry
     * from the features before the phase; a handler that defines a new name during the phase,
     * as {@code .MACRO} does, registers here too.
     *
     * @return The registry, owned by this context and used by the preprocessor for every lookup.
     */
    public PreProcessorHandlerRegistry handlers() {
        return handlers;
    }

    /**
     * Returns the pre-lexed tokens of every file that may be included, keyed by resolved
     * absolute path.
     *
     * @return The map passed to the constructor, returned as given rather than copied; empty
     *         for a context created without such files.
     */
    public Map<String, List<Token>> fileTokens() {
        return fileTokens;
    }

    /**
     * Returns the lines of a file, for the entry of an inclusion of it.
     *
     * @param resolvedPath The resolved absolute path of the file.
     * @return The file's lines, the same list for every call with the path; empty for a file the
     *         context holds no lines of.
     */
    public List<String> linesOf(String resolvedPath) {
        return fileLines.getOrDefault(resolvedPath, List.of());
    }

    /**
     * Returns the entry of the main file, which the preprocessor lists first.
     *
     * @return The entry built by the constructor.
     */
    SourceFile mainFile() {
        return mainFile;
    }

    /**
     * Returns the options of the compilation.
     *
     * @return The options passed to the constructor.
     */
    public CompilerOptions options() {
        return options;
    }

    /**
     * Returns a new instance number for a set of tokens injected together. The number is unique
     * within one preprocessor run, so that the positions of two injections of the same text stay
     * apart: a handler that injects tokens stamps them with it as the expansion of their
     * {@link org.evochora.compiler.api.SourceInfo}. The numbers count from 1; 0 stands for
     * tokens that were not given one.
     *
     * @return A number not returned before by this context, one more than the last.
     */
    public int nextInstance() {
        return ++lastInstance;
    }

    // --- Flags ---

    /**
     * Returns the flags of the phase: one store for the whole token stream, seeded from the
     * options of the compilation, which the directives of a feature fill as the walk reaches
     * them.
     *
     * @return The flags of the phase.
     */
    public Flags flags() {
        return values.flags();
    }

    /**
     * Computes a number at read time: the tokens of one value, a number, a flag name or a
     * computation, read with the operations of the compilation, every name a flag of
     * {@link #flags()} with its value. An error is reported to the diagnostics of the
     * preprocessor that walks the stream, at the token or the node at fault, or else at the
     * first token: tokens that form no computation, a name that is no flag or a flag without a
     * value, a computation that fails, a result that is no number. A computation that gives a
     * number, and a flag name written alone, are noted for the source view as {@code [=n]} right
     * after their last token; a number written alone is not, nor is a value outside every file.
     *
     * @param tokens The tokens of the value, as the directive delimits them; not empty.
     * @return The number, or empty if an error was reported.
     * @throws IllegalStateException if no preprocessor walks a stream with this context.
     */
    public Optional<Long> evaluate(List<Token> tokens) {
        return switch (values.read(tokens)) {
            case ReadTimeValues.Reading.Number number -> {
                requireWalk();
                if (number.form() != ReadTimeValues.Reading.Form.NUMBER && tokens.getLast().source().inAFile()) {
                    notes.accept(tokens.getLast().after(), "[=" + number.value() + "]");
                }
                yield Optional.of(number.value());
            }
            case ReadTimeValues.Reading.Failed failed -> {
                requireWalk();
                SourceInfo at = failed.at() != null ? failed.at()
                        : tokens.isEmpty() ? null : tokens.getFirst().source();
                diagnostics.reportError(failed.message(), at == null ? "" : at.fileName(),
                        at == null ? 0 : at.lineNumber());
                yield Optional.empty();
            }
        };
    }

    private void requireWalk() {
        if (diagnostics == null) {
            throw new IllegalStateException("No preprocessor reports for this context.");
        }
    }

    /**
     * Sets where {@link #evaluate} reports and notes: to the diagnostics and the notes of the
     * preprocessor that walks a stream with this context.
     *
     * @param diagnostics The preprocessor's diagnostics.
     * @param notes       Records a note at a position for the source view.
     */
    void reportTo(DiagnosticsEngine diagnostics, BiConsumer<SourceInfo, String> notes) {
        this.diagnostics = diagnostics;
        this.notes = notes;
    }

    // --- Feature state ---

    /**
     * Returns the state object a feature keeps under the given key type, creating it with the
     * factory on the first request. One instance exists per key for the lifetime of this
     * context; the core knows nothing of the type and never reads the object.
     *
     * @param key     The class used as the key.
     * @param factory Creates the state object; called only while no object exists for the key.
     * @param <T>     The type of the state object.
     * @return The existing or newly created state object.
     */
    public <T> T getOrCreate(Class<T> key, Supplier<T> factory) {
        return key.cast(featureState.computeIfAbsent(key, k -> factory.get()));
    }

    // --- Inclusions ---

    /**
     * Records that the tokens of a file are being inlined at the current position. The
     * inclusion stays open until {@link #leaveInclusion()} is called, so that a file which
     * includes itself, directly or through other files, is recognised.
     * <p>
     * An inclusion that carries an alias chain enters a module: names inside it are qualified
     * by that chain until the inclusion is left, and the handlers it defines, its macros, are
     * its own. An inclusion without one keeps the enclosing module context and defines into it.
     *
     * @param inclusion The included file and the alias chain of the module it enters, if any.
     */
    public void enterInclusion(PlacementContext inclusion) {
        inclusions.push(inclusion);
        if (inclusion.aliasChain() != null) {
            handlers.enterModule();
        }
    }

    /**
     * Closes the innermost open inclusion; if it entered a module, that module's definitions
     * go with it. Leaving with no inclusion open is ignored, so a stream whose closing marker
     * has no matching opening does not fail here.
     */
    public void leaveInclusion() {
        if (!inclusions.isEmpty()) {
            PlacementContext left = inclusions.pop();
            if (left.aliasChain() != null) {
                handlers.leaveModule();
            }
        }
    }

    /**
     * Reports whether a file is currently being inlined, at any depth. Including it again
     * would inline it into itself.
     *
     * @param resolvedPath The resolved absolute path of the file.
     * @return {@code true} if an inclusion of that file is open.
     */
    public boolean isIncluding(String resolvedPath) {
        for (PlacementContext inclusion : inclusions) {
            if (inclusion.sourcePath().equals(resolvedPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the current import alias chain (e.g., "PRED.MATH"): the chain of the innermost
     * open inclusion that entered a module, or the root chain while no module has been entered.
     *
     * @return The chain qualifying names at the current position. Never null; the root chain
     *         may be empty.
     */
    public String currentAliasChain() {
        for (PlacementContext inclusion : inclusions) {
            if (inclusion.aliasChain() != null) {
                return inclusion.aliasChain();
            }
        }
        return rootAliasChain;
    }

}
