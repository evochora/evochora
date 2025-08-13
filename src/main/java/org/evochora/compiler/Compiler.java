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

        // TODO: Phase 4 (Code-Generierung) implementieren.
        diagnostics.reportError("Code-Generierung ist noch nicht implementiert.", programName, 1);
        throw new CompilationException(diagnostics.summary());
    }

    @Override
    public void setVerbosity(int level) {
        this.verbosity = level;
    }
}