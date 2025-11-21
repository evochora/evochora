package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ICompiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.PregNode;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.irgen.IrConverterRegistry;
import org.evochora.compiler.frontend.irgen.IrGenerator;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.frontend.tokenmap.TokenMapGenerator;

import java.util.ArrayList;
import org.evochora.compiler.frontend.postprocess.AstPostProcessor;
import org.evochora.compiler.ir.IrProgram;
import org.evochora.compiler.backend.layout.LayoutEngine;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.Linker;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.backend.link.LinkingRegistry;
import org.evochora.compiler.backend.emit.EmissionRegistry;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.emit.Emitter;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.util.DebugDump;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The main compiler implementation. This class orchestrates the entire compilation
 * pipeline from source code to a program artifact. It is not thread-safe.
 */
public class Compiler implements ICompiler {

    private final DiagnosticsEngine diagnostics = new DiagnosticsEngine();
    private int verbosity = -1;

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
     *
     * @param sourceLines The lines of source code to compile.
     * @param programName The name of the program, used for diagnostics and artifact metadata.
     * @param envProps The environment properties, providing context like world dimensions. Can be null.
     * @return The compiled program artifact.
     * @throws CompilationException if any errors occur during compilation.
     */
    public ProgramArtifact compile(List<String> sourceLines, String programName, EnvironmentProperties envProps) throws CompilationException {

        if (verbosity >= 0) {
            org.evochora.compiler.diagnostics.CompilerLogger.setLevel(verbosity);
        }
        // org.evochora.compiler.diagnostics.CompilerLogger.info("Compiler: " + programName);

        // Calculate absolute program name first (needed for Lexer and sources map)
        Path basePath;
        Path absoluteProgramName;
        try {
            Path pn = Path.of(programName);
            if (pn.isAbsolute()) {
                absoluteProgramName = pn;
                basePath = pn.getParent() != null ? pn.getParent() : pn.toAbsolutePath();
            } else {
                Path abs = pn.toAbsolutePath();
                absoluteProgramName = abs;
                basePath = abs.getParent() != null ? abs.getParent() : Path.of("").toAbsolutePath();
            }
        } catch (Exception ignored) {
            absoluteProgramName = Path.of(programName).toAbsolutePath();
            basePath = Path.of("").toAbsolutePath();
        }
        
        // Phase 1: Lexical Analysis
        String fullSource = String.join("\n", sourceLines) + "\n";
        Lexer initialLexer = new Lexer(fullSource, diagnostics, absoluteProgramName.toString().replace('\\', '/'));
        List<Token> initialTokens = initialLexer.scanTokens();
        
        // Phase 2: Preprocessing (includes, macros)
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, basePath);
        List<Token> processedTokens = preProcessor.expand();

        Map<String, List<String>> sources = new HashMap<>();
        // Use absolute path for main file to ensure consistency with included files
        sources.put(absoluteProgramName.toString().replace('\\', '/'), sourceLines);
        preProcessor.getIncludedFileContents().forEach((path, content) ->
                sources.put(path, Arrays.asList(content.split("\\r?\\n"))));

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 3: Parsing (builds AST)
        Parser parser = new Parser(processedTokens, diagnostics, basePath);
        List<AstNode> ast = parser.parse();

        // parser.getGlobalRegisterAliases() is used later when extracting aliases; no local copy needed here

        Map<String, List<org.evochora.compiler.api.ParamInfo>> procNameToParamNames = new HashMap<>();
        parser.getProcedureTable().forEach((name, procNode) -> {
            List<org.evochora.compiler.api.ParamInfo> params = new ArrayList<>();
            
            // Add REF parameters first (they come first in the procedure definition)
            if (procNode.refParameters() != null) {
                params.addAll(procNode.refParameters().stream()
                        .map(token -> new org.evochora.compiler.api.ParamInfo(token.text(), org.evochora.compiler.api.ParamType.REF))
                        .collect(Collectors.toList()));
            }
            
            // Add VAL parameters second
            if (procNode.valParameters() != null) {
                params.addAll(procNode.valParameters().stream()
                        .map(token -> new org.evochora.compiler.api.ParamInfo(token.text(), org.evochora.compiler.api.ParamType.VAL))
                        .collect(Collectors.toList()));
            }
            
            // Add old WITH syntax parameters last (for backward compatibility)
            if (procNode.parameters() != null) {
                params.addAll(procNode.parameters().stream()
                        .map(token -> new org.evochora.compiler.api.ParamInfo(token.text(), org.evochora.compiler.api.ParamType.WITH))
                        .collect(Collectors.toList()));
            }
            
            procNameToParamNames.put(name.toUpperCase(), params);
        });

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 4: Semantic Analysis (symbol resolution, type checking)
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics, symbolTable);
        analyzer.analyze(ast);
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }
        
        // Phase 5: Token Map Generation (for debugger)
        TokenMapGenerator tokenMapGenerator = new TokenMapGenerator(symbolTable, analyzer.getScopeMap(), diagnostics);
        Map<SourceInfo, TokenInfo> tokenMap = tokenMapGenerator.generateAll(ast);

        // Phase 6: AST Post-Processing (resolve register aliases)
        // Extract register aliases from parser (both .REG and .PREG)
        Map<String, String> astRegisterAliases = new HashMap<>();
        
        // Extract global register aliases from parser
        parser.getGlobalRegisterAliases().forEach((aliasName, registerToken) -> {
            astRegisterAliases.put(aliasName, registerToken.text());
        });
        
        // Extract procedure register aliases from AST
        extractProcedureRegisterAliases(ast, astRegisterAliases);
        
        // Create final alias map for emitter (convert register names to IDs)
        Map<String, Integer> finalAliasMap = new HashMap<>();
        for (Map.Entry<String, String> entry : astRegisterAliases.entrySet()) {
            String aliasName = entry.getKey();
            String registerName = entry.getValue();
            // Convert register name (e.g., "%PR0", "%FPR0") to register ID
            if (registerName.startsWith("%FPR")) {
                int registerIndex = Integer.parseInt(registerName.substring(4));
                int registerId = org.evochora.runtime.isa.Instruction.FPR_BASE + registerIndex;
                finalAliasMap.put(aliasName, registerId);
            } else if (registerName.startsWith("%PR")) {
                int registerIndex = Integer.parseInt(registerName.substring(3));
                int registerId = org.evochora.runtime.isa.Instruction.PR_BASE + registerIndex;
                finalAliasMap.put(aliasName, registerId);
            } else if (registerName.startsWith("%DR")) {
                int registerId = Integer.parseInt(registerName.substring(3));
                finalAliasMap.put(aliasName, registerId);
            }
        }
        
        AstPostProcessor astPostProcessor = new AstPostProcessor(symbolTable, astRegisterAliases);
        
        // Process all AST nodes, not just the first one
        for (int i = 0; i < ast.size(); i++) {
            ast.set(i, astPostProcessor.process(ast.get(i)));
        }

        // Phase 7: IR Generation (convert AST to intermediate representation)
        IrConverterRegistry irRegistry = IrConverterRegistry.initializeWithDefaults();
        IrGenerator irGenerator = new IrGenerator(diagnostics, irRegistry);
        IrProgram irProgram = irGenerator.generate(ast, programName);

        // Phase 8: IR Rewriting (apply emission rules)
        EmissionRegistry emissionRegistry = EmissionRegistry.initializeWithDefaults();
        java.util.List<org.evochora.compiler.ir.IrItem> rewritten = irProgram.items();
        LinkingContext linkingContext = new LinkingContext();
        for (IEmissionRule rule : emissionRegistry.rules()) {
            rewritten = rule.apply(rewritten, linkingContext);
        }
        IrProgram rewrittenIr = new IrProgram(programName, rewritten);

        // Phase 9: Layout (assign addresses to instructions)
        LayoutEngine layoutEngine = new LayoutEngine();
        LayoutResult layout = layoutEngine.layout(rewrittenIr, new RuntimeInstructionSetAdapter(), envProps);

        // Phase 10: Linking (resolve cross-references)
        LinkingRegistry linkingRegistry = LinkingRegistry.initializeWithDefaults(symbolTable);
        Linker linker = new Linker(linkingRegistry);
        IrProgram linkedIr = linker.link(rewrittenIr, layout, linkingContext, envProps);

        // Phase 11: Emission (generate final binary)
        Emitter emitter = new Emitter();
        ProgramArtifact artifact;
        try {
            // Generate tokenLookup from tokenMap for efficient line-based lookup
            Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup = TokenMapGenerator.buildTokenLookup(tokenMap);
            artifact = emitter.emit(linkedIr, layout, linkingContext, new RuntimeInstructionSetAdapter(), finalAliasMap, procNameToParamNames, sources, tokenMap, tokenLookup);
        } catch (org.evochora.compiler.api.CompilationException ce) {
            throw ce; // already formatted with file/line
        } catch (RuntimeException re) {
            // If any runtime exception bubbles up, wrap into CompilationException to present user-friendly message
            throw new org.evochora.compiler.api.CompilationException(re.getMessage(), re);
        }

        org.evochora.compiler.diagnostics.CompilerLogger.debug("Compiler: " + programName + " programId:" + artifact.programId());
        DebugDump.dumpProgramArtifact(programName, artifact);
        return artifact;
    }
    
    /**
     * Extracts procedure register aliases from the AST and adds them to the register aliases map.
     * This allows the AstPostProcessor to resolve procedure register aliases.
     *
     * @param ast the AST to extract aliases from
     * @param registerAliases the map to populate with procedure register aliases
     */
    private void extractProcedureRegisterAliases(List<AstNode> ast, Map<String, String> registerAliases) {
        for (AstNode node : ast) {
            if (node == null) continue;
            
            // Check if this is a PregNode
            if (node instanceof PregNode pregNode) {
                String aliasName = pregNode.alias().text();
                int registerIndex = pregNode.registerIndexValue();
                String targetRegister = "%PR" + registerIndex;
                registerAliases.put(aliasName, targetRegister);
            }
            
            // Recursively check children
            extractProcedureRegisterAliases(node.getChildren(), registerAliases);
        }
    }
    
    
    


    /**
     * {@inheritDoc}
     */
    @Override
    public void setVerbosity(int level) {
        this.verbosity = level;
    }
}
