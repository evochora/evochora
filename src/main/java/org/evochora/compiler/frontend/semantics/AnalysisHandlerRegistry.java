package org.evochora.compiler.frontend.semantics;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;

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
     * Bulk-registers pass-2 analysis handlers from a feature registry map.
     *
     * @param handlers Map of AST node classes to their analysis handlers.
     */
    public void registerAll(Map<Class<? extends AstNode>, IAnalysisHandler> handlers) {
        this.handlers.putAll(handlers);
    }

    /**
     * Bulk-registers pass-1 symbol collectors from a feature registry map.
     *
     * @param collectors Map of AST node classes to their symbol collectors.
     */
    public void registerAllCollectors(Map<Class<? extends AstNode>, ISymbolCollector> collectors) {
        this.collectors.putAll(collectors);
    }
}
