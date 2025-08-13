package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.TreeWalker;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionSignature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Führt die semantische Analyse durch, indem er dem TreeWalker spezialisierte
 * Handler für die Analyse von AST-Knoten bereitstellt.
 */
public class SemanticAnalyzer {

    private final DiagnosticsEngine diagnostics;
    private final SymbolTable symbolTable;
    private final TreeWalker definitionCollector;
    private final TreeWalker analyzer; // Zweiter TreeWalker für die Analyse

    public SemanticAnalyzer(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.symbolTable = new SymbolTable(diagnostics);

        // Handler für Phase 1: Definitionen sammeln
        Map<Class<? extends AstNode>, Consumer<AstNode>> definitionHandlers = new HashMap<>();
        definitionHandlers.put(LabelNode.class, node -> {
            symbolTable.define(new Symbol(((LabelNode) node).labelToken(), Symbol.Type.LABEL));
        });
        this.definitionCollector = new TreeWalker(definitionHandlers);

        // Handler für Phase 2: Analyse (Arity Check, etc.)
        Map<Class<? extends AstNode>, Consumer<AstNode>> analysisHandlers = new HashMap<>();
        analysisHandlers.put(InstructionNode.class, this::analyzeInstructionNode);
        this.analyzer = new TreeWalker(analysisHandlers);
    }

    /**
     * Startet die Analyse für eine Liste von AST-Knoten.
     */
    public void analyze(List<AstNode> statements) {
        // Phase 1: Alle Definitionen sammeln und Scopes verwalten.
        collectDefinitions(statements);

        // Phase 2: Den Baum durchlaufen und semantische Prüfungen durchführen.
        analyzer.walk(statements);
    }

    private void collectDefinitions(List<AstNode> nodes) {
        for (AstNode node : nodes) {
            if (node == null) continue;

            if (node instanceof LabelNode) {
                symbolTable.define(new Symbol(((LabelNode) node).labelToken(), Symbol.Type.LABEL));
            }

            if (node instanceof ProcedureNode || node instanceof ScopeNode) {
                symbolTable.enterScope();
                collectDefinitions(node.getChildren());
                symbolTable.leaveScope();
            } else {
                collectDefinitions(node.getChildren());
            }
        }
    }

    private void analyzeInstructionNode(AstNode node) {
        InstructionNode instructionNode = (InstructionNode) node;
        String opcodeName = instructionNode.opcode().text().toUpperCase();
        Integer opcodeId = Instruction.getInstructionIdByName(opcodeName);

        if (opcodeId == null) {
            // Dieser Fehler sollte bereits vom Parser gemeldet werden, aber zur Sicherheit.
            diagnostics.reportError("Unknown instruction '" + opcodeName + "'", "Unknown", instructionNode.opcode().line());
            return;
        }

        Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(opcodeId);
        if (signatureOpt.isPresent()) {
            InstructionSignature signature = signatureOpt.get();
            int expectedArity = signature.getArity();
            int actualArity = instructionNode.arguments().size();

            if (expectedArity != actualArity) {
                diagnostics.reportError(
                        String.format("Instruction '%s' expects %d argument(s), but got %d.",
                                instructionNode.opcode().text(), expectedArity, actualArity),
                        "Unknown",
                        instructionNode.opcode().line()
                );
            }
        }
    }
}