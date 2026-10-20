package org.evochora.compiler.frontend.lexer;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceLoader;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Lexer phase: finds the files of a compilation and turns each of them into tokens, once.
 * It tokenizes the main file with the {@link Tokenizer}, walks its statement lines, and hands a
 * line that holds a directive word a scan handler is registered under to that handler, wherever
 * the word stands in the line. A handler that finds a file to include asks the context to read
 * it, which tokenizes it if it has not been, and to read it as a placement of its own; so the
 * files are found while they are read, as every assembler does. The phase knows no directive:
 * which words name a file, and what the line around them has to look like, is the handlers'.
 * <p>
 * The result is the dependency graph with the tokens and the lines of every file, from which
 * the preprocessor builds the one token stream of the program.
 */
public final class Lexer {

    private final DiagnosticsEngine diagnostics;
    private final SourceRootResolver resolver;
    private final Map<String, IDependencyScanHandler> handlers;
    private final CompilerOptions options;
    private final IInstructionSet isa;
    private final Set<String> symbols;

    /**
     * Creates a lexer whose entire directive knowledge comes from the supplied handlers.
     *
     * @param diagnostics The engine errors are reported to.
     * @param resolver    Resolves the paths written in directives.
     * @param handlers    The scan handlers by the directive word they are registered under, in
     *                    upper case.
     * @param options     The options of the compilation, offered to the handlers through the
     *                    context.
     * @param isa         The instruction set the tokenizer tells opcodes and registers with.
     * @param symbols     The registered lexer symbols the tokenizer emits as symbol tokens.
     */
    public Lexer(DiagnosticsEngine diagnostics, SourceRootResolver resolver,
                 Map<String, IDependencyScanHandler> handlers, CompilerOptions options,
                 IInstructionSet isa, Set<String> symbols) {
        this.diagnostics = diagnostics;
        this.resolver = resolver;
        this.handlers = handlers;
        this.options = Objects.requireNonNull(options, "options");
        this.isa = isa;
        this.symbols = symbols;
    }

    /**
     * Reads the main file and every file it leads to. A module file is read as a placement at
     * every import, under the alias chain the import gives it, after the placements it imports
     * itself; the main file is the last placement.
     *
     * @param mainContent    The text of the main file.
     * @param mainPath       The resolved path of the main file.
     * @param rootAliasChain The alias chain of the main module; empty without a prefix.
     * @return The placements and the tokens and lines of every file; without placements when an
     *         error was reported.
     */
    public DependencyGraph read(String mainContent, String mainPath, String rootAliasChain) {
        State state = new State();
        state.tokens.put(mainPath, tokenize(mainPath, mainContent, state));
        readModule(state, rootAliasChain, mainPath);
        if (diagnostics.hasErrors()) {
            return new DependencyGraph(List.of(), Map.of(), Map.of(), mainPath);
        }
        return new DependencyGraph(List.copyOf(state.placements), Collections.unmodifiableMap(state.tokens),
                Collections.unmodifiableMap(state.lines), mainPath);
    }

    /**
     * What the reading has found so far: the placements, the tokens and lines of every file
     * read, the files on the way to the one being read, and the state of the features.
     */
    private static final class State {
        final List<ModulePlacement> placements = new ArrayList<>();
        final Map<String, List<Token>> tokens = new LinkedHashMap<>();
        final Map<String, List<String>> lines = new LinkedHashMap<>();
        /** The files on the way to the one being read, modules and source files alike. */
        final Set<String> importPath = new HashSet<>();
        final Map<Class<?>, Object> featureState = new HashMap<>();
    }

    private List<Token> tokenize(String path, String content, State state) {
        state.lines.putIfAbsent(path, List.of(content.split("\\r?\\n")));
        String text = content.endsWith("\n") ? content : content + "\n";
        List<Token> tokens = new Tokenizer(text, diagnostics, path, isa, symbols).scanTokens();
        Tokenizer.stripEofToken(tokens);
        return tokens;
    }

    private void readModule(State state, String aliasChain, String path) {
        if (!state.importPath.add(path)) {
            diagnostics.reportError("Circular dependency detected: " + path, path, 0);
            return;
        }
        List<IDependencyInfo> dependencies = readLines(state, aliasChain, path, false);
        state.placements.add(new ModulePlacement(aliasChain, path, dependencies));
        state.importPath.remove(path);
    }

    /**
     * Reads a {@code .SOURCE} file in the placement that includes it. A file that is already on
     * the way to this one, as a module or as a source file, is a circular inclusion and is reported
     * instead of being read again.
     */
    private void readSourceFile(State state, String aliasChain, String path, int lineNumber, String includer) {
        if (!state.importPath.add(path)) {
            diagnostics.reportError("Circular .SOURCE detected: " + path, includer, lineNumber);
            return;
        }
        readLines(state, aliasChain, path, true);
        state.importPath.remove(path);
    }

    /**
     * Walks the statement lines of a file. A line that holds a registered directive word goes to
     * that word's handler, with the line; a line a handler takes through the context is not
     * offered again.
     */
    private List<IDependencyInfo> readLines(State state, String aliasChain, String path, boolean sourceFileMode) {
        Context ctx = new Context(state, aliasChain, path, sourceFileMode, state.tokens.get(path));
        List<Token> line;
        while ((line = ctx.nextLine()) != null) {
            for (int i = 0; i < line.size(); i++) {
                Token token = line.get(i);
                if (token.type() != TokenType.DIRECTIVE) continue;
                IDependencyScanHandler handler = handlers.get(token.text().toUpperCase(Locale.ROOT));
                if (handler != null) {
                    handler.handle(ctx.lineOf(i), ctx);
                    break;
                }
            }
        }
        return ctx.collected;
    }

    private String loadContent(String resolvedPath) throws IOException {
        if (SourceLoader.isHttpUrl(resolvedPath)) {
            return SourceLoader.loadHttp(resolvedPath).content();
        }
        Path filePath = Path.of(resolvedPath);
        if (Files.exists(filePath)) {
            return SourceLoader.loadFile(filePath).content();
        }
        throw new IOException("File not found: " + resolvedPath);
    }

    /**
     * The context of one file being read: a cursor over its statement lines, and the way to the
     * files and the state.
     */
    private final class Context implements IDependencyScanContext {
        private final State state;
        private final String aliasChain;
        private final String sourcePath;
        private final boolean sourceFileMode;
        private final List<Token> tokens;
        private int next;
        private int lineStart;
        private int lineNumber;
        private final List<IDependencyInfo> collected = new ArrayList<>();

        Context(State state, String aliasChain, String sourcePath, boolean sourceFileMode, List<Token> tokens) {
            this.state = state;
            this.aliasChain = aliasChain;
            this.sourcePath = sourcePath;
            this.sourceFileMode = sourceFileMode;
            this.tokens = tokens;
        }

        @Override
        public String resolve(String path) throws SourceRootResolver.UnknownPrefixException {
            return resolver.resolve(path, sourcePath);
        }

        @Override
        public List<Token> loadTokens(String resolvedPath) throws IOException {
            List<Token> loaded = state.tokens.get(resolvedPath);
            if (loaded == null) {
                loaded = tokenize(resolvedPath, loadContent(resolvedPath), state);
                state.tokens.put(resolvedPath, loaded);
            }
            return loaded;
        }

        @Override
        public void reportError(String message) {
            diagnostics.reportError(message, sourcePath, lineNumber);
        }

        @Override
        public void scanNestedModule(String resolvedPath, String placementChain) {
            readModule(state, placementChain, resolvedPath);
        }

        @Override
        public void scanNestedSourceFile(String resolvedPath) {
            readSourceFile(state, aliasChain, resolvedPath, lineNumber, sourcePath);
        }

        @Override
        public String placementChain() {
            return aliasChain;
        }

        @Override
        public void addDependency(IDependencyInfo info) {
            if (sourceFileMode && !info.allowedInSourceFile()) {
                reportError(info.directiveName() + " not allowed in a .SOURCE file");
                return;
            }
            collected.add(info);
        }

        @Override
        public String sourcePath() {
            return sourcePath;
        }

        @Override
        public int lineNumber() {
            return lineNumber;
        }

        @Override
        public List<Token> nextLine() {
            while (next < tokens.size()) {
                int start = next;
                int end = start;
                while (end < tokens.size() && tokens.get(end).type() != TokenType.NEWLINE) {
                    end++;
                }
                next = Math.min(end + 1, tokens.size());
                if (end > start) {
                    lineStart = start;
                    lineNumber = tokens.get(start).source().lineNumber();
                    return tokens.subList(start, end);
                }
            }
            return null;
        }

        @Override
        public DirectiveLine lineOf(int indexInLine) {
            return DirectiveLine.of(tokens, lineStart + indexInLine);
        }

        @Override
        public CompilerOptions options() {
            return options;
        }

        @Override
        public <T> T getOrCreate(Class<T> key, Supplier<T> factory) {
            return key.cast(state.featureState.computeIfAbsent(key, k -> factory.get()));
        }
    }
}
