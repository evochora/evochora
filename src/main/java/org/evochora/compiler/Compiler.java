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
    // -1 means: do not override global logger level
    private int verbosity = -1;

    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException {

        if (verbosity >= 0) {
            org.evochora.compiler.diagnostics.CompilerLogger.setLevel(verbosity);
        }
        org.evochora.compiler.diagnostics.CompilerLogger.info("Start compile: " + programName + ", lines=" + sourceLines.size());

        // VOR PHASE 1: Preprocessing (Hier werden Tokens direkt modifiziert)
        // Anmerkung: Dieser Schritt ist etwas speziell, da er vor dem eigentlichen Lexing des Haupt-Codes
        // stattfindet. Eine noch sauberere Architektur würde den Preprocessor den Source-String modifizieren
        // lassen, aber für den Moment ist dieser Ansatz pragmatisch.
        String fullSource = String.join("\n", sourceLines);
        Lexer initialLexer = new Lexer(fullSource, diagnostics);
        List<Token> initialTokens = initialLexer.scanTokens();
        org.evochora.compiler.diagnostics.CompilerLogger.debug("Lexed tokens: " + initialTokens.size());
        // TRACE: dump a sample of tokens
        {
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(20, initialTokens.size());
            for (int i = 0; i < limit; i++) {
                Token t = initialTokens.get(i);
                sb.append('[').append(t.type()).append(':').append(t.text()).append("] ");
            }
            org.evochora.compiler.diagnostics.CompilerLogger.trace("Tokens[0.." + (limit - 1) + "]: " + sb);
        }

        // Wir brauchen einen Basispfad, um .INCLUDE-Dateien zu finden.
        // Vorerst nehmen wir das aktuelle Arbeitsverzeichnis.
        Path basePath = Path.of("").toAbsolutePath();

        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, basePath);
        List<Token> processedTokens = preProcessor.expand();
        org.evochora.compiler.diagnostics.CompilerLogger.debug("Preprocessed tokens: " + processedTokens.size());

        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 2: Parsing (arbeitet mit den vom Preprocessor bereinigten Tokens)
        Parser parser = new Parser(processedTokens, diagnostics);
        List<AstNode> ast = parser.parse();
        org.evochora.compiler.diagnostics.CompilerLogger.debug("Parsed AST nodes: " + ast.size());
        {
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(20, ast.size());
            for (int i = 0; i < limit; i++) sb.append('[').append(ast.get(i).getClass().getSimpleName()).append("] ");
            org.evochora.compiler.diagnostics.CompilerLogger.trace("AST[0.." + (limit - 1) + "]: " + sb);
        }
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 3: Semantische Analyse
        SemanticAnalyzer analyzer = new SemanticAnalyzer(diagnostics);
        analyzer.analyze(ast);
        org.evochora.compiler.diagnostics.CompilerLogger.debug("Semantic analysis completed");
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 4a: IR-Generierung (Vorbereitung für spätere Backend-Phasen)
        IrConverterRegistry irRegistry = IrConverterRegistry.initializeWithDefaults();
        IrGenerator irGenerator = new IrGenerator(diagnostics, irRegistry);
        IrProgram irProgram = irGenerator.generate(ast, programName);
        org.evochora.compiler.util.DebugDump.dumpIr(programName, "01_ir_generated", irProgram.items());
        {
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(20, irProgram.items().size());
            for (int i = 0; i < limit; i++) {
                var it = irProgram.items().get(i);
                if (it instanceof org.evochora.compiler.ir.IrInstruction ins) sb.append("INS:").append(ins.opcode());
                else if (it instanceof org.evochora.compiler.ir.IrDirective d) sb.append("DIR:").append(d.namespace()).append(':').append(d.name());
                else if (it instanceof org.evochora.compiler.ir.IrLabelDef l) sb.append("LBL:").append(l.name());
                else sb.append(it.getClass().getSimpleName());
                sb.append(" | ");
            }
            org.evochora.compiler.diagnostics.CompilerLogger.trace("IR[0.." + (limit - 1) + "]: " + sb);
        }

        // Phase 2: Emission Rules (rewrites) BEFORE layout
        EmissionRegistry emissionRegistry = EmissionRegistry.initializeWithDefaults();
        java.util.List<org.evochora.compiler.ir.IrItem> rewritten = irProgram.items();
        // create linking context early as emission rules may annotate it (e.g., callSiteBindings later)
        LinkingContext linkingContext = new LinkingContext();
        for (IEmissionRule rule : emissionRegistry.rules()) {
            rewritten = rule.apply(rewritten, linkingContext);
        }
        org.evochora.compiler.diagnostics.CompilerLogger.debug("Emission rewrites applied: items=" + rewritten.size());
        IrProgram rewrittenIr = new IrProgram(programName, rewritten);
        org.evochora.compiler.util.DebugDump.dumpIr(programName, "02_ir_rewritten", rewrittenIr.items());

        // Phase 3: Layout (now includes rewritten instructions)
        LayoutEngine layoutEngine = new LayoutEngine();
        LayoutResult layout = layoutEngine.layout(rewrittenIr, new RuntimeInstructionSetAdapter());
        org.evochora.compiler.diagnostics.CompilerLogger.debug("Layout: codeSlots=" + layout.linearAddressToCoord().size() + ", labels=" + layout.labelToAddress().size());
        {
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (var e : layout.labelToAddress().entrySet()) {
                if (shown++ >= 10) break;
                sb.append(e.getKey()).append("->").append(e.getValue()).append("; ");
            }
            org.evochora.compiler.diagnostics.CompilerLogger.trace("Labels(sample): " + sb);
        }

        // Phase 4: Linking
        LinkingRegistry linkingRegistry = LinkingRegistry.initializeWithDefaults();
        Linker linker = new Linker(linkingRegistry);
        IrProgram linkedIr = linker.link(rewrittenIr, layout, linkingContext);
        org.evochora.compiler.diagnostics.CompilerLogger.debug("Linking completed: items=" + linkedIr.items().size());
        org.evochora.compiler.util.DebugDump.dumpIr(programName, "03_ir_linked", linkedIr.items());

        // Phase 5: Machine code emission
        Emitter emitter = new Emitter();
        ProgramArtifact artifact = emitter.emit(linkedIr, layout, linkingContext, new RuntimeInstructionSetAdapter());
        org.evochora.compiler.diagnostics.CompilerLogger.info("Emit completed: programId=" + artifact.programId() + ", codeWords=" + artifact.machineCodeLayout().size());
        org.evochora.compiler.util.DebugDump.dumpProgramArtifact(programName, artifact);
        return artifact;
    }

    @Override
    public void setVerbosity(int level) {
        this.verbosity = level;
    }
}