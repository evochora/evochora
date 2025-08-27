package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.frontend.semantics.analysis.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path; // NEW IMPORT

/**
 * Performs semantic analysis on the AST. This includes tasks like symbol table management,
 * type checking (in a broader sense), and ensuring that the program logic is sound.
 * It operates by traversing the AST and dispatching nodes to specific handlers.
 */
public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final Map<Class<? extends AstNode>, IAnalysisHandler> handlers = new HashMap<>();
    private final Map<AstNode, SymbolTable.Scope> scopeMap = new HashMap<>();

    /**
     * Constructs a new semantic analyzer.
     * @param diagnostics The diagnostics engine for reporting errors.
     * @param symbolTable The symbol table to use for analysis.
     */
    public SemanticAnalyzer(DiagnosticsEngine diagnostics, SymbolTable symbolTable) {
        this.diagnostics = diagnostics;
        this.symbolTable = symbolTable;
        registerDefaultHandlers();
    }

    private void registerDefaultHandlers() {
        handlers.put(DefineNode.class, new DefineAnalysisHandler());
        handlers.put(LabelNode.class, new LabelAnalysisHandler());
        handlers.put(ScopeNode.class, new ScopeAnalysisHandler(this.scopeMap));
        handlers.put(ProcedureNode.class, new ProcedureAnalysisHandler(this.scopeMap));
        handlers.put(InstructionNode.class, new InstructionAnalysisHandler(symbolTable, diagnostics));
    }

    /**
     * Analyzes the given list of AST statements.
     * This is the main entry point for the semantic analysis phase.
     * It performs two passes: one to collect top-level symbols (labels, procedures),
     * and a second to analyze the statements in detail.
     * @param statements The list of top-level AST nodes to analyze.
     */
    public void analyze(List<AstNode> statements) {
        collectLabels(statements);
        symbolTable.resetScope();
        traverseAndAnalyze(statements);
    }

    private void collectLabels(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;

            if (node instanceof ProcedureNode proc) {
                symbolTable.define(new Symbol(proc.name(), Symbol.Type.PROCEDURE));
                symbolTable.registerProcedureMeta(proc.name(), proc.exported());
            }
            if (node instanceof RequireNode req) {
                if (req.alias() != null && req.path() != null && req.path().value() instanceof String) {
                    String aliasU = req.alias().text().toUpperCase();
                    String file = req.alias().fileName();
                    String target = (String) req.path().value();

                    // CORRECTION: Ensure that the path is normalized
                    String normalizedTarget = Path.of(target).normalize().toString().replace('\\', '/');
                    symbolTable.registerRequireAlias(file, aliasU, normalizedTarget);
                }
            }
            if (node instanceof LabelNode lbl) {
                symbolTable.define(new Symbol(lbl.labelToken(), Symbol.Type.LABEL));
            }

            if (node instanceof ScopeNode || node instanceof ProcedureNode) {
                SymbolTable.Scope newScope = symbolTable.enterScope();
                scopeMap.put(node, newScope);

                if (node instanceof ProcedureNode proc2 && proc2.parameters() != null) {
                    for (org.evochora.compiler.frontend.lexer.Token p : proc2.parameters()) {
                        symbolTable.define(new Symbol(p, Symbol.Type.VARIABLE));
                    }
                }

                collectLabels(node.getChildren());
                symbolTable.leaveScope();
            } else {
                collectLabels(node.getChildren());
            }
        }
    }

    private void traverseAndAnalyze(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;
            IAnalysisHandler handler = handlers.get(node.getClass());
            if (handler != null) {
                handler.analyze(node, symbolTable, diagnostics);
            }
            traverseAndAnalyze(node.getChildren());
            if (handler instanceof ScopeAnalysisHandler scopeHandler) {
                scopeHandler.afterChildren(symbolTable);
            }
        }
    }
}