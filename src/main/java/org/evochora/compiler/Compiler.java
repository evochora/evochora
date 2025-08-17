package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ICompiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.irgen.IrConverterRegistry;
import org.evochora.compiler.frontend.irgen.IrGenerator;
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

public class Compiler implements ICompiler {

    private final DiagnosticsEngine diagnostics = new DiagnosticsEngine();
    private int verbosity = -1;

    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException {

        if (verbosity >= 0) {
            org.evochora.compiler.diagnostics.CompilerLogger.setLevel(verbosity);
        }
        org.evochora.compiler.diagnostics.CompilerLogger.info("Start compile: " + programName);

        String fullSource = String.join("\n", sourceLines);
        Lexer initialLexer = new Lexer(fullSource, diagnostics, programName);
        List<Token> initialTokens = initialLexer.scanTokens();

        Path basePath = Path.of("").toAbsolutePath();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, basePath);
        List<Token> processedTokens = preProcessor.expand();

        Map<String, List<String>> sources = new HashMap<>();
        sources.put(programName, sourceLines);
        preProcessor.getIncludedFileContents().forEach((path, content) ->
                sources.put(Path.of(path).getFileName().toString(), Arrays.asList(content.split("\\r?\\n"))));

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        Parser parser = new Parser(processedTokens, diagnostics);
        List<AstNode> ast = parser.parse();

        Map<String, Token> registerAliases = parser.getRegisterAliasTable();
        Map<String, Integer> finalAliasMap = registerAliases.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Integer.parseInt(entry.getValue().text().substring(3))
                ));

        Map<String, List<String>> procNameToParamNames = new HashMap<>();
        parser.getProcedureTable().forEach((name, procNode) -> {
            List<String> paramNames = procNode.parameters().stream()
                    .map(Token::text)
                    .collect(Collectors.toList());
            procNameToParamNames.put(name.toUpperCase(), paramNames);
        });

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        IrConverterRegistry irRegistry = IrConverterRegistry.initializeWithDefaults();
        IrGenerator irGenerator = new IrGenerator(diagnostics, irRegistry);
        IrProgram irProgram = irGenerator.generate(ast, programName);

        EmissionRegistry emissionRegistry = EmissionRegistry.initializeWithDefaults();
        java.util.List<org.evochora.compiler.ir.IrItem> rewritten = irProgram.items();
        LinkingContext linkingContext = new LinkingContext();
        for (IEmissionRule rule : emissionRegistry.rules()) {
            rewritten = rule.apply(rewritten, linkingContext);
        }
        IrProgram rewrittenIr = new IrProgram(programName, rewritten);

        LayoutEngine layoutEngine = new LayoutEngine();
        LayoutResult layout = layoutEngine.layout(rewrittenIr, new RuntimeInstructionSetAdapter());

        LinkingRegistry linkingRegistry = LinkingRegistry.initializeWithDefaults();
        Linker linker = new Linker(linkingRegistry);
        IrProgram linkedIr = linker.link(rewrittenIr, layout, linkingContext);

        Emitter emitter = new Emitter();
        ProgramArtifact artifact = emitter.emit(linkedIr, layout, linkingContext, new RuntimeInstructionSetAdapter(), finalAliasMap, procNameToParamNames, sources);

        org.evochora.compiler.diagnostics.CompilerLogger.info("Emit completed: programId=" + artifact.programId());
        DebugDump.dumpProgramArtifact(programName, artifact);
        return artifact;
    }

    @Override
    public void setVerbosity(int level) {
        this.verbosity = level;
    }
}
