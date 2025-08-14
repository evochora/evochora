package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ICompiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.preprocessor.PreProcessor; // NEU
import org.evochora.compiler.frontend.semantics.SemanticAnalyzer;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
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

import java.nio.file.Path; // NEU
import java.util.List;

/**
 * Die Hauptimplementierung der ICompiler-Schnittstelle.
 */
public class Compiler implements ICompiler {

    private final DiagnosticsEngine diagnostics = new DiagnosticsEngine();
    private int verbosity = 1;

    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException {

        // VOR PHASE 1: Preprocessing (Hier werden Tokens direkt modifiziert)
        // Anmerkung: Dieser Schritt ist etwas speziell, da er vor dem eigentlichen Lexing des Haupt-Codes
        // stattfindet. Eine noch sauberere Architektur würde den Preprocessor den Source-String modifizieren
        // lassen, aber für den Moment ist dieser Ansatz pragmatisch.
        String fullSource = String.join("\n", sourceLines);
        Lexer initialLexer = new Lexer(fullSource, diagnostics);
        List<Token> initialTokens = initialLexer.scanTokens();

        // Wir brauchen einen Basispfad, um .INCLUDE-Dateien zu finden.
        // Vorerst nehmen wir das aktuelle Arbeitsverzeichnis.
        Path basePath = Path.of("").toAbsolutePath();

        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, basePath);
        List<Token> processedTokens = preProcessor.expand();

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 2: Parsing (arbeitet mit den vom Preprocessor bereinigten Tokens)
        Parser parser = new Parser(processedTokens, diagnostics);
        List<AstNode> ast = parser.parse();
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 3: Semantische Analyse
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 4a: IR-Generierung (Vorbereitung für spätere Backend-Phasen)
        IrConverterRegistry irRegistry = IrConverterRegistry.initializeWithDefaults();
        IrGenerator irGenerator = new IrGenerator(diagnostics, irRegistry);
        IrProgram irProgram = irGenerator.generate(ast, programName);

        // Phase 2: Layout
        LayoutEngine layoutEngine = new LayoutEngine();
        LayoutResult layout = layoutEngine.layout(irProgram);

        // Phase 2b: Linking
        LinkingRegistry linkingRegistry = LinkingRegistry.initializeWithDefaults();
        LinkingContext linkingContext = new LinkingContext();
        Linker linker = new Linker(linkingRegistry);
        IrProgram linkedIr = linker.link(irProgram, layout, linkingContext);

        // Phase 3: Emission Rules (rewrites)
        EmissionRegistry emissionRegistry = EmissionRegistry.initializeWithDefaults();
        java.util.List<org.evochora.compiler.ir.IrItem> rewritten = linkedIr.items();
        for (IEmissionRule rule : emissionRegistry.rules()) {
            rewritten = rule.apply(rewritten, linkingContext);
        }
        IrProgram finalIr = new IrProgram(programName, rewritten);

        // Phase 4: Machine code emission
        Emitter emitter = new Emitter();
        ProgramArtifact artifact = emitter.emit(finalIr, layout, linkingContext, new RuntimeInstructionSetAdapter());
        return artifact;
    }

    @Override
    public void setVerbosity(int level) {
        this.verbosity = level;
    }
}