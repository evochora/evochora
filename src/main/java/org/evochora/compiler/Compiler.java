package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
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
import org.evochora.compiler.frontend.module.ModuleDescriptor;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorHandlerRegistry;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessorResult;
import org.evochora.compiler.frontend.semantics.AnalysisHandlerRegistry;
import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.ModuleSetupRegistry;
import org.evochora.compiler.model.ModuleContextTracker;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.diagnostics.CompilerLogger;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.module.IDependencyInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ir.IrItem;
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
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.emit.Emitter;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The main compiler implementation. This class orchestrates the entire compilation
 * pipeline from source code to a program artifact. It is not thread-safe.
 */
public class Compiler implements ICompiler {

    private final DiagnosticsEngine diagnostics = new DiagnosticsEngine();
    private int verbosity = -1;

    /**
     * {@inheritDoc}
     */
    @Override
    public ProgramArtifact compile(String programPath, EnvironmentProperties envProps, CompilerOptions options)
            throws CompilationException, IOException {
        String resolvedPath;
        if (options != null) {
            // Explicit source roots: resolve via source roots relative to CWD
            SourceRootResolver resolver = new SourceRootResolver(
                    options.sourceRoots(), Path.of("").toAbsolutePath());
            try {
                resolvedPath = resolver.resolve(programPath, "");
            } catch (SourceRootResolver.UnknownPrefixException e) {
                throw new CompilationException(e.getMessage());
            }
        } else {
            // No source roots: treat programPath as CWD-relative file path
            SourceRootResolver.ParsedPath parsed = SourceRootResolver.parsePath(programPath);
            resolvedPath = Path.of(parsed.filePath()).toAbsolutePath().normalize()
                    .toString().replace('\\', '/');
        }

        List<String> sourceLines = Files.readAllLines(Path.of(resolvedPath));
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

        // Derive root alias chain from the source root prefix in programName
        SourceRootResolver.ParsedPath parsedProgram = SourceRootResolver.parsePath(programName);
        String rootAliasChain = (parsedProgram.prefix() != null) ? parsedProgram.prefix() : "";

        // Determine working directory and resolve programName to actual file path
        final Path workingDirectory;
        final String mainFilePath;
        if (options != null) {
            // Explicit source roots: resolve relative to CWD
            workingDirectory = Path.of("").toAbsolutePath();
            SourceRootResolver initResolver = new SourceRootResolver(
                    effectiveOptions.sourceRoots(), workingDirectory);
            try {
                mainFilePath = initResolver.resolve(programName, "");
            } catch (SourceRootResolver.UnknownPrefixException e) {
                throw new CompilationException(e.getMessage());
            }
        } else {
            // No source roots: resolve relative to program file's parent
            String resolvedFilePath;
            Path wd;
            try {
                Path programFile = Path.of(parsedProgram.filePath()).toAbsolutePath().normalize();
                resolvedFilePath = programFile.toString().replace('\\', '/');
                wd = programFile.getParent() != null
                        ? programFile.getParent()
                        : Path.of("").toAbsolutePath();
            } catch (Exception e) {
                resolvedFilePath = programName;
                wd = Path.of("").toAbsolutePath();
            }
            mainFilePath = resolvedFilePath;
            workingDirectory = wd;
        }
        SourceRootResolver resolver = new SourceRootResolver(
                effectiveOptions.sourceRoots(), workingDirectory);

        String fullSource = String.join("\n", sourceLines) + "\n";

        // Feature registration
        FeatureRegistry featureRegistry = new FeatureRegistry();
        StandardFeatures.all().forEach(f -> f.register(featureRegistry));

        // Phase 0: Dependency Scanning (load imported modules)
        DependencyScanner depScanner = new DependencyScanner(diagnostics, resolver, featureRegistry.dependencyScanHandlers());
        DependencyGraph graph = depScanner.scan(fullSource, mainFilePath);
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 1: Lexical Analysis — lex all files, store results
        Map<String, List<Token>> moduleTokens = new HashMap<>();
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            if (module.id().path().equals(mainFilePath)) continue;
            String moduleSource = module.content();
            if (!moduleSource.endsWith("\n")) moduleSource += "\n";
            Lexer moduleLexer = new Lexer(moduleSource, diagnostics, module.sourcePath());
            List<Token> tokens = moduleLexer.scanTokens();
            Lexer.stripEofToken(tokens);
            moduleTokens.put(module.sourcePath(), tokens);
        }

        // Phase 1b: Lex .SOURCE files (collected during dependency scanning)
        Map<String, List<Token>> sourceTokens = new HashMap<>();
        for (Map.Entry<String, String> entry : depScanner.sourceContents().entrySet()) {
            String sourcePath = entry.getKey();
            String sourceContent = entry.getValue();
            if (!sourceContent.endsWith("\n")) sourceContent += "\n";
            Lexer sourceLexer = new Lexer(sourceContent, diagnostics, sourcePath);
            List<Token> tokens = sourceLexer.scanTokens();
            Lexer.stripEofToken(tokens);
            sourceTokens.put(sourcePath, tokens);
        }

        Lexer mainLexer = new Lexer(fullSource, diagnostics, mainFilePath);
        List<Token> initialTokens = new ArrayList<>(mainLexer.scanTokens());

        // Phase 2: Preprocessing (includes, macros)
        PreProcessorHandlerRegistry ppRegistry = new PreProcessorHandlerRegistry();
        featureRegistry.preprocessorHandlers().forEach(ppRegistry::register);
        PreProcessorContext ppContext = new PreProcessorContext(rootAliasChain, moduleTokens, sourceTokens);
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, resolver,
                ppRegistry, ppContext);
        PreProcessorResult ppResult = preProcessor.expand();

        Map<String, List<String>> sources = new HashMap<>();
        sources.put(mainFilePath, sourceLines);
        for (ModuleDescriptor module : graph.topologicalOrder()) {
            if (!module.id().path().equals(mainFilePath)) {
                sources.put(module.sourcePath(), Arrays.asList(module.content().split("\\r?\\n")));
            }
        }
        depScanner.sourceContents().forEach((path, content) ->
                sources.putIfAbsent(path, Arrays.asList(content.split("\\r?\\n"))));

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 3: Parsing (builds AST)
        ParserStatementRegistry parserRegistry = new ParserStatementRegistry();
        featureRegistry.parserStatementHandlers().forEach(parserRegistry::register);
        if (featureRegistry.defaultParserStatementHandler() != null) {
            parserRegistry.registerDefault(featureRegistry.defaultParserStatementHandler());
        }
        Parser parser = new Parser(ppResult.tokens(), diagnostics, parserRegistry);
        List<AstNode> ast = parser.parse();

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 4: Semantic Analysis (symbol resolution, type checking)
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        AnalysisHandlerRegistry analysisRegistry = new AnalysisHandlerRegistry();
        analysisRegistry.registerAll(featureRegistry.analysisHandlers());
        analysisRegistry.registerAllCollectors(featureRegistry.symbolCollectors());
        ModuleSetupRegistry setupRegistry = new ModuleSetupRegistry();
        featureRegistry.dependencySetupHandlers().forEach((type, handler) -> registerSetupHandler(setupRegistry, type, handler));
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable, graph, mainFilePath, rootAliasChain, analysisRegistry, setupRegistry);
        analyzer.analyze(ast);
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }
        symbolTable.freeze();

        // Phase 5: Token Map Generation (for debugger)
        TokenMapContributorRegistry tokenMapRegistry = new TokenMapContributorRegistry();
        tokenMapRegistry.registerAll(featureRegistry.tokenMapContributors());
        ModuleContextTracker tokenMapTracker = new ModuleContextTracker(symbolTable);
        symbolTable.setCurrentModule(rootAliasChain);
        TokenMapGenerator tokenMapGenerator = new TokenMapGenerator(symbolTable, diagnostics, tokenMapRegistry, tokenMapTracker);
        Map<SourceInfo, TokenInfo> tokenMap = tokenMapGenerator.generateAll(ast);

        // Phase 6: AST Post-Processing (resolve register aliases and constants)
        PostProcessHandlerRegistry postProcessRegistry = new PostProcessHandlerRegistry();
        postProcessRegistry.registerAll(featureRegistry.postProcessHandlers());
        ModuleContextTracker postProcessTracker = new ModuleContextTracker(symbolTable);
        symbolTable.setCurrentModule(rootAliasChain);
        AstPostProcessor astPostProcessor = new AstPostProcessor(symbolTable, postProcessTracker, postProcessRegistry);

        // Process all AST nodes, not just the first one
        for (int i = 0; i < ast.size(); i++) {
            ast.set(i, astPostProcessor.process(ast.get(i)));
        }

        // Phase 7: IR Generation (convert AST to intermediate representation)
        IrConverterRegistry irRegistry = IrConverterRegistry.initialize(new DefaultAstNodeToIrConverter());
        irRegistry.registerAll(featureRegistry.irConverters());
        IrGenerator irGenerator = new IrGenerator(diagnostics, irRegistry);
        IrProgram irProgram = irGenerator.generate(ast, programName, rootAliasChain);

        // Phase 8: IR Rewriting (apply emission rules)
        EmissionRegistry emissionRegistry = new EmissionRegistry();
        emissionRegistry.registerAll(featureRegistry.emissionRules());
        List<IrItem> rewritten = irProgram.items();
        for (IEmissionRule rule : emissionRegistry.rules()) {
            rewritten = rule.apply(rewritten);
        }
        IrProgram rewrittenIr = new IrProgram(programName, rewritten);

        // Phase 9: Layout (assign addresses to instructions)
        LayoutDirectiveRegistry layoutRegistry = new LayoutDirectiveRegistry((directive, context) -> {
            // IR directives without a layout handler are silently skipped — not every
            // directive needs layout-phase processing (e.g., core:proc_enter, core:org)
        });
        layoutRegistry.registerAll(featureRegistry.layoutHandlers());
        RuntimeInstructionSetAdapter isa = new RuntimeInstructionSetAdapter();
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
        LinkingContext linkContext = new LinkingContext(symbolTable, isa);
        linkContext.pushAliasChain(rootAliasChain);
        IrProgram linkedIr = linker.link(rewrittenIr, layout, linkContext, envProps);
        linkContext.freeze();

        // Phase 11: Emission (generate final binary)
        EmissionContributorRegistry emissionContributorRegistry = new EmissionContributorRegistry();
        featureRegistry.emissionContributors().forEach(emissionContributorRegistry::register);
        Emitter emitter = new Emitter();
        ProgramArtifact artifact;
        try {
            // Generate tokenLookup from tokenMap for efficient line-based lookup
            Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup = TokenMapGenerator.buildTokenLookup(tokenMap);
            artifact = emitter.emit(linkedIr, layout, linkContext, isa, emissionContributorRegistry, sources, tokenMap, tokenLookup);
        } catch (CompilationException ce) {
            throw ce; // already formatted with file/line
        } catch (RuntimeException re) {
            // If any runtime exception bubbles up, wrap into CompilationException to present user-friendly message
            throw new CompilationException(re.getMessage(), re);
        }

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
