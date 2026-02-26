package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.InstructionNode;
import org.evochora.compiler.frontend.parser.ast.PregNode;
import org.evochora.compiler.frontend.parser.features.def.DefineNode;
import org.evochora.compiler.frontend.parser.features.label.LabelNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.evochora.compiler.frontend.parser.features.reg.RegNode;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;
import org.evochora.compiler.frontend.parser.features.scope.ScopeNode;
import org.evochora.compiler.frontend.semantics.analysis.*;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry mapping AST node classes to analysis handlers (pass 2) and symbol collectors (pass 1).
 * Follows the same pattern as {@link org.evochora.compiler.frontend.irgen.IrConverterRegistry}.
 */
public final class AnalysisHandlerRegistry {

    private final Map<Class<? extends AstNode>, IAnalysisHandler> handlers = new HashMap<>();
    private final Map<Class<? extends AstNode>, ISymbolCollector> collectors = new HashMap<>();

    /**
     * Registers a pass-2 analysis handler for the given AST node class.
     *
     * @param nodeType The concrete AST node class.
     * @param handler  The handler instance.
     * @param <T>      Concrete AST type parameter.
     */
    public <T extends AstNode> void register(Class<T> nodeType, IAnalysisHandler handler) {
        handlers.put(nodeType, handler);
    }

    /**
     * Registers a pass-1 symbol collector for the given AST node class.
     *
     * @param nodeType  The concrete AST node class.
     * @param collector The collector instance.
     * @param <T>       Concrete AST type parameter.
     */
    public <T extends AstNode> void registerCollector(Class<T> nodeType, ISymbolCollector collector) {
        collectors.put(nodeType, collector);
    }

    /**
     * Resolves the pass-2 handler for the given node class.
     *
     * @param nodeType The AST node class to look up.
     * @return Optional handler if registered.
     */
    public Optional<IAnalysisHandler> resolveHandler(Class<? extends AstNode> nodeType) {
        return Optional.ofNullable(handlers.get(nodeType));
    }

    /**
     * Resolves the pass-1 collector for the given node class.
     *
     * @param nodeType The AST node class to look up.
     * @return Optional collector if registered.
     */
    public Optional<ISymbolCollector> resolveCollector(Class<? extends AstNode> nodeType) {
        return Optional.ofNullable(collectors.get(nodeType));
    }

    /**
     * Creates a registry pre-populated with the default handlers and collectors.
     *
     * @param symbolTable The symbol table used by scope-aware handlers.
     * @param scopeMap    The scope map shared between collectors and handlers.
     * @param diagnostics The diagnostics engine for handlers that need it at construction time.
     * @return A fully initialized registry.
     */
    public static AnalysisHandlerRegistry initializeWithDefaults(
            SymbolTable symbolTable,
            Map<AstNode, SymbolTable.Scope> scopeMap,
            DiagnosticsEngine diagnostics) {

        AnalysisHandlerRegistry registry = new AnalysisHandlerRegistry();

        // Pass-1 collectors
        registry.registerCollector(ProcedureNode.class, new ProcedureSymbolCollector(scopeMap));
        registry.registerCollector(LabelNode.class, new LabelSymbolCollector());
        registry.registerCollector(RequireNode.class, new RequireSymbolCollector());
        registry.registerCollector(ScopeNode.class, new ScopeSymbolCollector(scopeMap));

        // Pass-2 handlers
        registry.register(DefineNode.class, new DefineAnalysisHandler());
        registry.register(RegNode.class, new RegAnalysisHandler());
        registry.register(LabelNode.class, new LabelAnalysisHandler());
        registry.register(ScopeNode.class, new ScopeAnalysisHandler(scopeMap));
        registry.register(ProcedureNode.class, new ProcedureAnalysisHandler(scopeMap));
        registry.register(InstructionNode.class, new InstructionAnalysisHandler(symbolTable, diagnostics));
        registry.register(PregNode.class, new PregAnalysisHandler());

        return registry;
    }
}
