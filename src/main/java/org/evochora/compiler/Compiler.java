package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.InternalCompilerException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ICompiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.frontend.module.DependencyGraph;
import org.evochora.compiler.frontend.module.DependencyScanner;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessorResult;
import org.evochora.compiler.frontend.semantics.AnalysisHandlerRegistry;
import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.ModuleSetupRegistry;
import org.evochora.compiler.frontend.module.ModuleContextTracker;
import org.evochora.compiler.frontend.semantics.ScopeTracker;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.diagnostics.CompilerLogger;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.diagnostics.ErrorRecoveryException;
import org.evochora.compiler.frontend.module.IDependencyInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.irgen.DefaultAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrConverterRegistry;
import org.evochora.compiler.frontend.irgen.IrGenerator;
import org.evochora.compiler.model.symbols.SymbolTable;
import org.evochora.compiler.frontend.tokenmap.TokenMapContributorRegistry;
import org.evochora.compiler.frontend.tokenmap.TokenMapGenerator;

import java.util.ArrayList;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.evochora.compiler.frontend.postprocess.PostProcessHandlerRegistry;
import org.evochora.compiler.model.ir.IrProgram;
import org.evochora.compiler.backend.layout.LayoutDirectiveRegistry;
import org.evochora.compiler.backend.layout.LayoutEngine;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.Linker;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.backend.link.LinkingDirectiveRegistry;
import org.evochora.compiler.backend.link.LinkingRegistry;
import org.evochora.compiler.backend.emit.EmissionContributorRegistry;
import org.evochora.compiler.backend.rewrite.IrRewriter;
import org.evochora.compiler.backend.rewrite.RewriteRegistry;
import org.evochora.compiler.backend.emit.Emitter;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The main compiler implementation. This class orchestrates the entire compilation
 * pipeline from source code to a program artifact. It keeps nothing between compilations,
 * so one instance may compile any number of programs, one after the other.
 */
public class Compiler implements ICompiler {

    private final List<ICompilerFeature> features;
    private int verbosity = -1;

    /**
     * Creates a compiler with the standard features.
     */
    public Compiler() {
        this(StandardFeatures.all());
    }

    /**
     * Creates a compiler with the given features, which tests use to put a feature of their
     * own next to the standard ones.
     *
     * @param features The features to register, in order.
     */
    Compiler(List<ICompilerFeature> features) {
        this.features = features;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProgramArtifact compile(String programPath, EnvironmentProperties envProps, CompilerOptions options)
            throws CompilationException, IOException {
        MainFile mainFile = locateMainFile(programPath, options);
        List<String> sourceLines = Files.readAllLines(Path.of(mainFile.path()));
        return compile(sourceLines, programPath, envProps, options);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation performs a context-free compilation, which is suitable
     * for syntax validation but will fail if context-dependent directives like
     * .ORG or .PLACE with wildcards are used.
     */
    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException {
        return compile(sourceLines, programName, null);
    }

    /**
     * Compiles the given source code into a program artifact with environment context.
     * Delegates to the 4-arg overload with default options.
     */
    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName, EnvironmentProperties envProps) throws CompilationException {
        return compile(sourceLines, programName, envProps, null);
    }

    /**
     * Compiles the given source code into a program artifact with environment context and options.
     *
     * @param sourceLines The lines of source code to compile.
     * @param programName The name of the program, used for diagnostics and artifact metadata.
     * @param envProps The environment properties, providing context like world dimensions. Can be null.
     * @param options Compiler options controlling source root resolution. Can be null for defaults.
     * @return The compiled program artifact.
     * @throws CompilationException if any errors occur during compilation.
     */
    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName, EnvironmentProperties envProps, CompilerOptions options) throws CompilationException {

        if (verbosity >= 0) {
            CompilerLogger.setLevel(verbosity);
        }

        CompilerOptions effectiveOptions = (options != null) ? options : CompilerOptions.defaults();
        effectiveOptions.validate();

        MainFile mainFile = locateMainFile(programName, options);
        try {
            return runPhases(sourceLines, programName, envProps, effectiveOptions, mainFile);
        } catch (ErrorRecoveryException unwound) {
            // Every phase that throws this catches it itself; one that reaches here is a defect too
            throw new InternalCompilerException(unwound);
        } catch (RuntimeException defect) {
            throw new InternalCompilerException(defect);
        }
    }

    /**
     * Runs the twelve phases in order and returns what the last one emits. A mistake in the
     * program surfaces as a {@link CompilationException} from {@link #failOnErrors()} or from
     * a backend phase; anything else that is thrown is a defect of the compiler.
     */
    private ProgramArtifact runPhases(List<String> sourceLines, String programName, EnvironmentProperties envProps,
                                      CompilerOptions effectiveOptions, MainFile mainFile) throws CompilationException {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        String rootAliasChain = mainFile.rootAliasChain();
        final String mainFilePath = mainFile.path();
        SourceRootResolver resolver = new SourceRootResolver(
                effectiveOptions.sourceRoots(), mainFile.workingDirectory());

        String fullSource = String.join("\n", sourceLines) + "\n";

        // Feature registration
        RuntimeInstructionSetAdapter isa = new RuntimeInstructionSetAdapter();
        FeatureRegistry featureRegistry = new FeatureRegistry(isa);
        features.forEach(f -> f.register(featureRegistry));

        // Phase 0: Dependency Scanning (load imported modules)
        DependencyScanner depScanner = new DependencyScanner(diagnostics, resolver, featureRegistry.dependencyScanHandlers());
        DependencyGraph graph = depScanner.scan(fullSource, mainFilePath);
        failOnErrors(diagnostics);

        // Phase 1: Lexical Analysis — every included file under its path, the main file as the stream
        Map<String, List<Token>> fileTokens = Lexer.lexFiles(graph.includedContents(), diagnostics, isa);
        List<Token> initialTokens = new ArrayList<>(new Lexer(fullSource, diagnostics, mainFilePath, isa).scanTokens());

        // Phase 2: Preprocessing (includes, macros)
        PreProcessorContext ppContext = new PreProcessorContext(rootAliasChain, fileTokens);
        featureRegistry.preprocessorHandlers().forEach(ppContext.handlers()::register);
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, resolver, ppContext);
        PreProcessorResult ppResult = preProcessor.expand();

        Map<String, String> sources = new HashMap<>(graph.includedContents());
        sources.put(mainFilePath, fullSource);

        failOnErrors(diagnostics);

        // Phase 3: Parsing (builds AST)
        ParserStatementRegistry parserRegistry = new ParserStatementRegistry();
        featureRegistry.parserStatementHandlers().forEach(parserRegistry::register);
        if (featureRegistry.defaultParserStatementHandler() != null) {
            parserRegistry.registerDefault(featureRegistry.defaultParserStatementHandler());
        }
        Parser parser = new Parser(ppResult.tokens(), diagnostics, parserRegistry);
        List<AstNode> ast = parser.parse();

        failOnErrors(diagnostics);

        // Phase 4: Semantic Analysis (symbol resolution, type checking)
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        AnalysisHandlerRegistry analysisRegistry = new AnalysisHandlerRegistry();
        analysisRegistry.registerAll(featureRegistry.analysisHandlers());
        analysisRegistry.registerAllCollectors(featureRegistry.symbolCollectors());
        ModuleSetupRegistry setupRegistry = new ModuleSetupRegistry();
        featureRegistry.dependencySetupHandlers().forEach((type, handler) -> registerSetupHandler(setupRegistry, type, handler));
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, graph, mainFilePath, rootAliasChain, analysisRegistry, setupRegistry);
        analyzer.analyze(ast);
        failOnErrors(diagnostics);
        symbolTable.freeze();

        // Phase 5: Token Map Generation (for debugger)
        TokenMapContributorRegistry tokenMapRegistry = new TokenMapContributorRegistry();
        tokenMapRegistry.registerAll(featureRegistry.tokenMapContributors());
        ModuleContextTracker tokenMapTracker = new ModuleContextTracker(symbolTable);
        TokenMapGenerator tokenMapGenerator = new TokenMapGenerator(symbolTable, diagnostics, tokenMapRegistry, tokenMapTracker);
        Map<SourceInfo, TokenInfo> tokenMap = tokenMapGenerator.generateAll(ast);
        failOnErrors(diagnostics);

        // Phase 6: AST Post-Processing (resolve register aliases and constants)
        PostProcessHandlerRegistry postProcessRegistry = new PostProcessHandlerRegistry();
        postProcessRegistry.registerAll(featureRegistry.postProcessHandlers());
        ModuleContextTracker postProcessTracker = new ModuleContextTracker(symbolTable);
        ScopeTracker scopeTracker = new ScopeTracker(symbolTable);
        AstPostProcessor astPostProcessor = new AstPostProcessor(symbolTable, postProcessTracker, scopeTracker, postProcessRegistry);
        List<AstNode> resolvedAst = astPostProcessor.process(ast);
        failOnErrors(diagnostics);

        // Phase 7: IR Generation (convert AST to intermediate representation)
        IrConverterRegistry irRegistry = IrConverterRegistry.initialize(new DefaultAstNodeToIrConverter());
        irRegistry.registerAll(featureRegistry.irConverters());
        IrGenerator irGenerator = new IrGenerator(diagnostics, irRegistry);
        IrProgram irProgram = irGenerator.generate(resolvedAst, programName, rootAliasChain);
        failOnErrors(diagnostics);

        // Phase 8: IR Rewriting (apply the rewrite rules of the features)
        RewriteRegistry rewriteRegistry = new RewriteRegistry();
        rewriteRegistry.registerAll(featureRegistry.rewriteRules());
        IrProgram rewrittenIr = new IrRewriter(rewriteRegistry).rewrite(irProgram, isa);

        // Phase 9: Layout (assign addresses to instructions)
        LayoutDirectiveRegistry layoutRegistry = new LayoutDirectiveRegistry((directive, context) -> {
            // IR directives without a layout handler are silently skipped — not every
            // directive needs layout-phase processing (e.g., core:proc_enter, core:org)
        });
        layoutRegistry.registerAll(featureRegistry.layoutHandlers());
        LayoutEngine layoutEngine = new LayoutEngine();
        LayoutResult layout = layoutEngine.layout(rewrittenIr, isa, envProps, layoutRegistry);

        // Phase 10: Linking (resolve cross-references)
        LinkingRegistry linkingRegistry = new LinkingRegistry();
        linkingRegistry.registerAll(featureRegistry.linkingRules());
        LinkingDirectiveRegistry linkingDirRegistry = new LinkingDirectiveRegistry((d, c) -> {
            // IR directives without a linking handler are silently skipped — not every
            // directive needs linking-phase processing (e.g., core:place, core:org)
        });
        linkingDirRegistry.registerAll(featureRegistry.linkingDirectiveHandlers());
        Linker linker = new Linker(linkingRegistry, linkingDirRegistry);
        LinkingContext linkContext = new LinkingContext(isa);
        IrProgram linkedIr = linker.link(layout, linkContext, programName);
        linkContext.freeze();

        // Phase 11: Emission (generate final binary)
        EmissionContributorRegistry emissionContributorRegistry = new EmissionContributorRegistry();
        featureRegistry.emissionContributors().forEach(emissionContributorRegistry::register);
        Emitter emitter = new Emitter();
        Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup = TokenMapGenerator.buildTokenLookup(tokenMap);
        ProgramArtifact artifact = emitter.emit(linkedIr, layout, linkContext, isa, emissionContributorRegistry, sources, tokenMap, tokenLookup);

        CompilerLogger.debug("Compiler: " + programName + " programId:" + artifact.programId());
        return artifact;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void setVerbosity(int level) {
        this.verbosity = level;
    }

    /**
     * Stops the compilation if any phase so far has reported an error. Called after every
     * phase that reports through the diagnostics engine, so that a later phase never runs on
     * input an earlier one has rejected. The backend phases throw instead of reporting.
     *
     * @throws CompilationException listing the errors reported so far
     */
    /**
     * Where the main file is and what it is called: its resolved path, the directory the
     * included files' paths are resolved against, and the alias chain the program's own names
     * are qualified with, which is the source root prefix of the program name if it has one.
     */
    private record MainFile(String path, Path workingDirectory, String rootAliasChain) {
    }

    /**
     * Locates the main file. With source roots the program name is resolved against them from
     * the current directory; without, it is a file path, and the included files are resolved
     * against that file's directory.
     */
    private static MainFile locateMainFile(String programName, CompilerOptions options) throws CompilationException {
        SourceRootResolver.ParsedPath parsed = SourceRootResolver.parsePath(programName);
        String rootAliasChain = parsed.prefix() != null ? parsed.prefix() : "";
        if (options != null) {
            Path workingDirectory = Path.of("").toAbsolutePath();
            SourceRootResolver resolver = new SourceRootResolver(options.sourceRoots(), workingDirectory);
            try {
                return new MainFile(resolver.resolve(programName, ""), workingDirectory, rootAliasChain);
            } catch (SourceRootResolver.UnknownPrefixException e) {
                throw new CompilationException(e.getMessage());
            }
        }
        try {
            Path programFile = Path.of(parsed.filePath()).toAbsolutePath().normalize();
            Path workingDirectory = programFile.getParent() != null ? programFile.getParent() : Path.of("").toAbsolutePath();
            return new MainFile(programFile.toString().replace('\\', '/'), workingDirectory, rootAliasChain);
        } catch (java.nio.file.InvalidPathException e) {
            throw new CompilationException("Invalid program path: " + programName + " — " + e.getMessage());
        }
    }

    private static void failOnErrors(DiagnosticsEngine diagnostics) throws CompilationException {
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }
    }

    /**
     * Type-safe bridge for registering setup handlers from the untyped FeatureRegistry map.
     * The cast is safe because FeatureRegistry.dependencySetupHandler() enforces type consistency
     * at registration time.
     */
    @SuppressWarnings("unchecked")
    private static <T extends IDependencyInfo> void registerSetupHandler(
            ModuleSetupRegistry registry, Class<T> type, IDependencySetupHandler<?> handler) {
        registry.register(type, (IDependencySetupHandler<T>) handler);
    }
}
