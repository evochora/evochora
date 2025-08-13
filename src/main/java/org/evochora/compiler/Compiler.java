package org.evochora.compiler;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ICompiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.core.phases.Lexer;
import org.evochora.compiler.core.phases.Parser;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.List;

/**
 * Die Hauptimplementierung der {@link ICompiler}-Schnittstelle.
 * Dies ist der neue, modulare Compiler, der die Legacy-Implementierung ersetzt.
 * <p>
 * Er orchestriert die verschiedenen Phasen des Kompilierungsprozesses.
 */
public class Compiler implements ICompiler {

    private final DiagnosticsEngine diagnostics = new DiagnosticsEngine();
    private int verbosity = 1;

    /**
     * {@inheritDoc}
     */
        /**
         * {@inheritDoc}
         */
    @Override
    public ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException {
        // TODO: [Phase 4] Temporäre Implementierung, wird schrittweise ausgebaut.

        // Phase 0: Preprocessing
        // TODO: Hier werden die Preprocessing-Handler aufgerufen (z.B. für .FILE)

        // Phase 1: Lexing
        String fullSource = String.join("\n", sourceLines);
        Lexer lexer = new Lexer(fullSource, diagnostics);
        List<org.evochora.compiler.core.Token> tokens = lexer.scanTokens();
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // Phase 2: Parsing
        Parser parser = new Parser(tokens, diagnostics);
        List<org.evochora.compiler.core.ast.AstNode> ast = parser.parse();
        if (diagnostics.hasErrors()) {
            throw new CompilationException(diagnostics.summary());
        }

        // TODO: Phase 3 (Semantische Analyse) und 4 (Code-Generierung) implementieren.
        diagnostics.reportError("Code-Generierung ist noch nicht implementiert.", programName, 1);
        throw new CompilationException(diagnostics.summary());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setVerbosity(int level) {
        this.verbosity = level;
    }
    
}
