package org.evochora.compiler.model.symbols;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.QualifiedNames;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ExpressionNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IPlacementAlias;
import org.evochora.compiler.model.ast.IdentifierNode;
import org.evochora.compiler.model.ast.LiteralNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module-aware symbol table for managing scopes and symbols during semantic analysis.
 *
 * <p>Names are held by levels: the module level of every placement and, inside it, one scope
 * per node that opens a level; levels nest. A lookup
 * resolves a name in one of two ways:</p>
 * <ul>
 *   <li>A plain name is searched from the scope it is written in outward to the module level;
 *       the innermost scope that holds it wins. A level sees its own names and those of every
 *       level it stands in.</li>
 *   <li>A dotted name is a path and is resolved segment by segment. Its first segment is searched
 *       like a plain name; the walk then descends from what it found: into a module through an
 *       alias of the module level that leads to a placement ({@link ModuleScope#aliases()}), and
 *       into a scope through a symbol whose node opened one ({@link #getNodeScope}). Each further
 *       segment is looked up in that level alone.</li>
 * </ul>
 * <p>{@code EXPORT} means the same on every level: the name is visible one level further out.
 * Every segment that leads into a level the writer does not stand in has to be exported; the
 * writer stands in a scope if it is the scope the path is written in or one enclosing it, and
 * never in a module reached through an alias. Whether a path from outside may continue through
 * an alias of a module is the {@code EXPORT} flag of the alias symbol.</p>
 *
 * <p>Modules are identified by their import alias chain (e.g., "PRED.MATH") rather than
 * by file path, allowing the same physical file to appear as distinct placements with
 * independent symbol namespaces.</p>
 *
 * <p>For single-file compilations, the table operates with a single default module, so a
 * caller that never imports anything need not name a module at all.</p>
 *
 * <p>The table enforces one rule on names itself, whatever they name: a name is one segment, so
 * a definition whose name contains a dot is reported when it is defined. A name may repeat a
 * name of an enclosing level; inside the level that defines it, the inner one is meant.</p>
 */
public class SymbolTable {

    /**
     * Represents a single scope in the symbol table: the module level, or a level a node opened.
     * The root scope, the module level of every placement, has the empty name {@link TokenInfo#MODULE_LEVEL};
     * every other scope is named by its path: the alias chain of the module it was opened in and
     * the segments of the scopes from the module level inward, joined by dots (e.g., "MAIN.INIT").
     * Scope identity is determined by object reference, not by name.
     */
    public static class Scope {
        private final Scope parent;
        private final String name;
        // name -> (module placement, or file outside a module -> symbol), in the order of definition
        private final Map<String, Map<String, Symbol>> symbols = new LinkedHashMap<>();

        Scope(Scope parent, String name) {
            this.parent = parent;
            this.name = name;
        }

        /**
         * Returns this scope's name, used for annotations and debug output, and, for any scope but
         * the root, as the path that qualifies the names defined in it.
         * Names carry no identity — scopes are compared by reference.
         *
         * @return the scope name, {@link TokenInfo#MODULE_LEVEL} for the root scope or the path of
         *         the level, e.g. "MAIN.INIT" for a procedure INIT of module MAIN
         */
        public String name() {
            return name;
        }
    }

    // --- Module-aware primary structure (keyed by alias chain) ---
    private final Map<String, ModuleScope> modules = new HashMap<>();
    private String currentAliasChain;
    private String rootAliasChain;

    // --- Scope hierarchy of the levels nodes open (within the current module) ---
    private final Scope rootScope;
    private Scope currentScope;

    // --- Node-to-scope mapping (populated by the symbol collector of a node that opens a level, consumed by TokenMapGenerator) ---
    // Keyed by node identity: AST nodes are records, so two structurally equal nodes would
    // otherwise share one entry and therefore one scope.
    private final Map<AstNode, Scope> nodeScopeMap = new IdentityHashMap<>();

    // --- Outcomes of computations (recorded by the analysis handlers that evaluate, read by later phases) ---
    // Keyed by node identity, as the scopes are: an outcome belongs to the computation where it
    // stands, and two structurally equal nodes are two computations.
    private final Map<ExpressionNode, ExpressionOutcome> expressionResults = new IdentityHashMap<>();
    // The values of definitions, keyed by the identity of the defining node.
    private final Map<AstNode, ExpressionOutcome> definitionValues = new IdentityHashMap<>();

    private final DiagnosticsEngine diagnostics;

    /**
     * The names nothing may define, upper-cased, each with what it is ("an operator", "a
     * function"); a name with the empty text, the cursor {@code @}, is reserved as it is.
     */
    private final Map<String, String> reservedNames;

    /**
     * How many definitions {@link #bindingOf} follows before it gives up; a chain longer than
     * this is a circle it failed to notice, and it returns empty as for one it did.
     */
    private static final int MAX_BINDING_DEPTH = 32;
    private final Set<String> reportedCircles = new HashSet<>();
    private boolean frozen = false;

    /**
     * Constructs a new symbol table that rejects the given names wherever anything is defined.
     * The current module must be set via {@link #setCurrentModule(String)} before any
     * define/resolve operations.
     * @param diagnostics   The diagnostics engine for reporting errors.
     * @param reservedNames The names nothing may define, in any letter case, each with what it
     *                      is, worded for the message: "an operator", "a function" for the names
     *                      of the operations of computations; the empty text for a name that is
     *                      reserved as it is, such as {@code @}, the cursor.
     */
    public SymbolTable(DiagnosticsEngine diagnostics, Map<String, String> reservedNames) {
        this.diagnostics = diagnostics;
        Map<String, String> reserved = new HashMap<>();
        reservedNames.forEach((name, what) -> reserved.put(name.toUpperCase(), what));
        this.reservedNames = Map.copyOf(reserved);
        this.rootScope = new Scope(null, TokenInfo.MODULE_LEVEL);
        this.currentScope = this.rootScope;
    }

    // === Freeze support ===

    /**
     * Freezes the symbol table, preventing structural modifications (define, registerModule,
     * enterScope, registerNodeScope). Cursor operations (setCurrentScope, leaveScope,
     * resetScope) and all reads remain allowed.
     * <p>
     * {@link #setCurrentModule(String)} remains allowed only for modules that are already
     * registered. Switching to an unknown alias chain has to create a scope for it and
     * therefore fails on a frozen table.
     */
    public void freeze() {
        this.frozen = true;
        modules.values().forEach(ModuleScope::freeze);
    }

    private void guardFrozen() {
        if (frozen) {
            throw new IllegalStateException("SymbolTable is frozen — no structural modifications allowed after Phase 4");
        }
    }

    // === Module management ===

    /**
     * Registers a module in the symbol table.
     * @param aliasChain The alias chain identifying this module placement (e.g., "PRED.MATH").
     * @param sourcePath The file path or URL of the module source.
     */
    public void registerModule(String aliasChain, String sourcePath) {
        guardFrozen();
        modules.computeIfAbsent(aliasChain, ac -> new ModuleScope(ac, sourcePath));
    }

    /**
     * Names the placement that is the root of the compilation.
     *
     * @param aliasChain The alias chain of the root placement.
     * @throws IllegalStateException if the table is frozen.
     */
    public void registerRoot(String aliasChain) {
        guardFrozen();
        this.rootAliasChain = aliasChain;
    }

    /**
     * Tells which placement is the root of the compilation.
     *
     * @return The alias chain given to {@link #registerRoot(String)}; {@code null} if none was
     *         given, as in a compilation of a single file without placements.
     */
    public String rootAliasChain() {
        return rootAliasChain;
    }

    /**
     * Sets the current module context. All subsequent define/resolve operations
     * operate within this module.
     * <p>
     * An alias chain that is not registered yet is registered on the spot, with the alias
     * chain itself standing in for the source path. Since symbols are keyed by that source
     * path, such a module keys its symbols by alias chain rather than by file.
     * @param aliasChain The alias chain of the module to set as current.
     */
    public void setCurrentModule(String aliasChain) {
        this.currentAliasChain = aliasChain;
        if (!modules.containsKey(aliasChain)) {
            guardFrozen();
            modules.put(aliasChain, new ModuleScope(aliasChain, aliasChain));
        }
    }

    /**
     * Gets the current module alias chain.
     *
     * @return the alias chain set by the last {@link #setCurrentModule(String)} call;
     *         the empty string for a root module compiled without a prefix, and
     *         {@code null} before any module has been set
     */
    public String getCurrentAliasChain() {
        return currentAliasChain;
    }

    /**
     * Gets the module scope for the given alias chain, or empty if not registered.
     *
     * @param aliasChain the alias chain identifying the module placement
     * @return the module scope, or empty if nothing is registered under that chain
     */
    public Optional<ModuleScope> getModuleScope(String aliasChain) {
        return Optional.ofNullable(modules.get(aliasChain));
    }

    // === Scope management ===

    /**
     * Resets the current scope to the root scope.
     */
    public void resetScope() {
        this.currentScope = this.rootScope;
    }

    /**
     * Enters a new scope inside the current one. The scope is named by its path: the path of the
     * current scope followed by the segment, or, on the module level, the current module's alias
     * chain followed by the segment.
     * @param segment The name the scope is opened under, e.g. the name of a procedure ("INIT");
     *                it is upper-cased.
     * @return The new scope.
     */
    public Scope enterScope(String segment) {
        guardFrozen();
        String key = segment.toUpperCase();
        String path = QualifiedNames.join(currentScope == rootScope ? currentAliasChain : currentScope.name, key);
        Scope newScope = new Scope(currentScope, path);
        currentScope = newScope;
        return newScope;
    }


    /**
     * Leaves the current scope and moves to the parent scope.
     */
    public void leaveScope() {
        if (currentScope.parent != null) {
            currentScope = currentScope.parent;
        }
    }

    /**
     * Sets the current scope to the given scope.
     * @param scope The scope to set as current.
     */
    public void setCurrentScope(Scope scope) {
        this.currentScope = scope;
    }

    /**
     * Gets the current scope.
     * @return The current scope.
     */
    public Scope getCurrentScope() {
        return this.currentScope;
    }

    /**
     * Gets the root scope.
     * @return The root scope.
     */
    public Scope getRootScope() {
        return this.rootScope;
    }

    /**
     * Associates an AST node with its scope. Called by the symbol collector of a node that opens a
     * level, as pass 1 walks the AST and reaches the node.
     *
     * @param node the AST node that opens the scope, used as the lookup key by identity
     * @param scope the scope traversal should enter when it reaches that node
     */
    public void registerNodeScope(AstNode node, Scope scope) {
        guardFrozen();
        nodeScopeMap.put(node, scope);
    }

    /**
     * Returns the scope associated with the given AST node, or null if none.
     * <p>
     * Nodes are matched by identity, so this has to be the very instance that was registered.
     * A node rebuilt from the same values, as tree rewriting produces, does not find it.
     *
     * @param node the AST node to look up
     * @return the scope registered for the node, or {@code null} if it opens none
     */
    public Scope getNodeScope(AstNode node) {
        return nodeScopeMap.get(node);
    }

    // === Outcomes of computations ===

    /**
     * Records what phase 4 made of a computation in operand position. Called by the analysis
     * handler that evaluates it; a computation that failed records nothing.
     *
     * @param node    The node of the computation, used as the lookup key by identity.
     * @param outcome Its value, or the tree a later phase computes with its kind.
     * @throws IllegalStateException if the table is frozen.
     */
    public void recordResult(ExpressionNode node, ExpressionOutcome outcome) {
        guardFrozen();
        expressionResults.put(node, outcome);
    }

    /**
     * Returns what phase 4 made of a computation in operand position.
     * <p>
     * Nodes are matched by identity, so this has to be the very instance that was evaluated, as
     * for {@link #getNodeScope}.
     *
     * @param node The node of the computation.
     * @return Its outcome; empty if it was not evaluated or failed.
     */
    public Optional<ExpressionOutcome> resultOf(ExpressionNode node) {
        return Optional.ofNullable(expressionResults.get(node));
    }

    /**
     * Records the value of a definition whose value is a computation, so that a name bound to it
     * stands for that value: a value now, or, for a value only a later phase knows, the
     * definition's qualified name (see {@link #chainEndOf}).
     *
     * @param definition The defining node, the node of its symbol, used as the key by identity.
     * @param outcome    Its value, or the tree a later phase computes with its kind.
     * @throws IllegalStateException if the table is frozen.
     */
    public void recordDefinitionValue(AstNode definition, ExpressionOutcome outcome) {
        guardFrozen();
        definitionValues.put(definition, outcome);
    }

    /**
     * Returns the value recorded for a definition.
     *
     * @param definition The defining node, matched by identity.
     * @return Its recorded value; empty if none was recorded.
     */
    public Optional<ExpressionOutcome> definitionValueOf(AstNode definition) {
        return Optional.ofNullable(definitionValues.get(definition));
    }

    // === Symbol definition and resolution ===

    /**
     * Defines a new symbol in the current scope and registers it in the current module scope.
     * A name that the same module placement, or outside a module the same file, has defined in
     * this scope already keeps its first definition;
     * the caller, which knows what kind of thing it tried to define, reports that.
     * <p>
     * A name is one segment: a name that contains a dot could only be confused with the path of
     * a name on another level. Such a definition is reported here, at the symbol's position,
     * whatever kind of thing it names, and is not filed. So is a reserved name: the name of an
     * operation, which a computation would read as the operation, and {@code @}, the cursor; the
     * message says what the name is.
     * @param symbol The symbol to define.
     * @return The definition the name already had in this scope, which stays; empty if the
     *         symbol was defined, or if its name contains a dot or is reserved and it was
     *         reported instead.
     * @throws IllegalStateException if the table is frozen.
     */
    public Optional<Symbol> define(Symbol symbol) {
        guardFrozen();
        if (symbol.name().indexOf('.') >= 0) {
            diagnostics.reportError(
                    "Cannot define '" + symbol.name() + "': a name is one segment and may not contain a dot.",
                    symbol.sourceInfo().fileName(), symbol.sourceInfo().lineNumber());
            return Optional.empty();
        }
        String name = symbol.name().toUpperCase();
        String reserved = reservedNames.get(name);
        if (reserved != null) {
            diagnostics.reportError(
                    reserved.isEmpty()
                            ? "'" + symbol.name() + "' cannot be defined."
                            : "'" + symbol.name() + "' is " + reserved + " and cannot be defined.",
                    symbol.sourceInfo().fileName(), symbol.sourceInfo().lineNumber());
            return Optional.empty();
        }
        String file = symbol.sourceInfo().fileName();

        // In module context, the symbol is filed under the module placement rather than the file
        // it stands in: .SOURCE-included symbols are resolvable from the module's own tokens, and
        // two placements of one file keep apart.
        ModuleScope modScope = modules.get(currentAliasChain);
        if (modScope != null) {
            file = modScope.aliasChain();
        }

        // Register in the scope hierarchy (for visibility on the level it is defined on)
        Map<String, Symbol> perFile = currentScope.symbols.computeIfAbsent(name, k -> new LinkedHashMap<>());
        Symbol existing = perFile.putIfAbsent(file, symbol);
        if (existing != null) {
            return Optional.of(existing);
        }

        // Register in the module scope (for cross-module visibility)
        if (modScope != null && currentScope == rootScope) {
            modScope.defineSymbol(name, symbol);
        }
        return Optional.empty();
    }

    /**
     * Follows what an identifier is bound to, through definitions that are themselves
     * identifiers, until a node that is not one is reached.
     *
     * @param reference The identifier as written.
     * @return The node the identifier finally stands for: what the last binding binds to, a
     *         literal for a definition whose recorded value is a value, or the identifier under
     *         the qualified name of a symbol that offers no binding (a label, a procedure) or of a
     *         definition whose recorded value only a later phase knows. Empty if the identifier
     *         names no symbol, if the last definition has no value, or if its definitions go in a
     *         circle, which is reported once at the definition the circle was entered through.
     */
    public Optional<AstNode> bindingOf(IdentifierNode reference) {
        return chainEndOf(reference).map(end -> end.symbol() != null
                ? new IdentifierNode(end.qualifiedName(), end.sourceInfo())
                : end.node());
    }

    /**
     * Where a chain of names ends, as {@link #chainEndOf} finds it: at the node a binding gave,
     * at a symbol whose node offers no binding, or at a definition whose recorded value only a
     * later phase knows.
     *
     * @param node          The node the chain ends at: what the last binding binds to, a literal
     *                      of a recorded value, or the node of the symbol the chain stops at,
     *                      whose capabilities and recorded value the caller asks.
     * @param symbol        The symbol the chain stops at: one that offers no binding, or a
     *                      definition whose recorded value only a later phase knows;
     *                      {@code null} when the chain ends at a node.
     * @param qualifiedName The qualified name of that symbol; {@code null} with it.
     * @param sourceInfo    The position of the last identifier of the chain, as written.
     */
    public record ChainEnd(AstNode node, Symbol symbol, String qualifiedName, SourceInfo sourceInfo) {
    }

    /**
     * Follows what an identifier is bound to, through definitions that are themselves
     * identifiers, and says where the chain ends. Each identifier is looked up where it is
     * written: the reference from the current scope, every identifier a definition binds to
     * from the scope of that definition, in the placement its position names.
     * <p>
     * A definition with a recorded value ends the chain: a value as a literal at the position of
     * the identifier that names it, a value only a later phase knows at the definition's symbol.
     * A definition whose value is a computation without a recorded outcome has no value: the
     * computation failed and was reported where it was evaluated.
     *
     * @param reference The identifier as written.
     * @return Where the chain ends: at the node the last binding binds to, at the literal of a
     *         recorded value, or at the symbol with its qualified name whose node offers no
     *         binding (a label, a procedure, a module alias) or whose recorded value only a later
     *         phase knows. Empty if the identifier names no symbol, if the last definition has no
     *         value, or if its definitions go in a circle, which is reported once at the
     *         definition the circle was entered through.
     */
    public Optional<ChainEnd> chainEndOf(IdentifierNode reference) {
        IdentifierNode current = reference;
        // The symbols visited, by identity, and the names as written, for the message
        Set<Symbol> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> chain = new ArrayList<>();
        SourceInfo firstDefinition = null;
        // The scope the identifier is looked up from: the current scope for the reference, the
        // scope of the definition for every identifier a definition binds to.
        Scope from = currentScope;
        for (int depth = 0; depth < MAX_BINDING_DEPTH; depth++) {
            // An identifier is looked up where it is written: in the placement its position
            // names, in the scope of the definition that binds to it.
            Lookup lookup = lookUp(current.text(), current.sourceInfo(), from);
            Optional<ResolvedSymbol> resolved = lookup.resolution().found();
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            Symbol symbol = resolved.get().symbol();
            if (!(symbol.node() instanceof IIdentifierBinding binding)) {
                return Optional.of(new ChainEnd(symbol.node(), symbol, resolved.get().qualifiedName(), current.sourceInfo()));
            }
            ExpressionOutcome recorded = definitionValues.get(symbol.node());
            if (recorded != null) {
                return Optional.of(recorded.value() != null
                        ? new ChainEnd(new LiteralNode(recorded.value(), current.sourceInfo()), null, null, current.sourceInfo())
                        : new ChainEnd(symbol.node(), symbol, resolved.get().qualifiedName(), current.sourceInfo()));
            }
            if (visited.contains(symbol)) {
                if (reportedCircles.add(symbol.sourceInfo() + " " + symbol.name())) {
                    diagnostics.reportError(
                            "Definition of " + chain.get(0) + " is circular: " + String.join(" -> ", chain) + " -> " + current.text() + ".",
                            firstDefinition != null ? firstDefinition.fileName() : "unknown",
                            firstDefinition != null ? firstDefinition.lineNumber() : 0);
                }
                return Optional.empty();
            }
            visited.add(symbol);
            chain.add(current.text());
            if (firstDefinition == null) {
                firstDefinition = symbol.sourceInfo();
            }
            from = lookup.scope();
            AstNode bound = binding.bind(current);
            if (!(bound instanceof IdentifierNode next)) {
                return bound == null || bound instanceof ExpressionNode
                        ? Optional.empty()
                        : Optional.of(new ChainEnd(bound, null, null, current.sourceInfo()));
            }
            current = next;
        }
        return Optional.empty();
    }

    /**
     * Resolves a name from the current scope: a plain name by searching from the current scope
     * outward to the module level, a dotted name as a path, segment by segment, through modules
     * and the scopes of symbols, every segment that leads into a level the writer does not stand
     * in being exported (e.g., {@code UTIL.CLAMP.TO_MIN}).
     * @param name The name of the symbol to resolve.
     * @param at   The position the name is written at. A position in a registered placement
     *             looks the name up among that placement's symbols, whichever file of the
     *             placement it stands in; a position in no registered placement looks it up among
     *             the symbols of its file.
     * @return The symbol with its qualified name, or the reason there is none.
     */
    public Resolution resolve(String name, SourceInfo at) {
        return lookUp(name, at).resolution();
    }

    /**
     * What a lookup found, together with the scope the symbol belongs to.
     *
     * @param resolution The symbol with its qualified name, or the reason there is none.
     * @param scope      The scope the symbol is defined in; {@code null} if nothing was found.
     */
    private record Lookup(Resolution resolution, Scope scope) {
        static Lookup missing(String explanation) {
            return new Lookup(new Resolution.Missing(explanation, false), null);
        }

        static Lookup undefined() {
            return new Lookup(new Resolution.Missing("the name is not defined.", true), null);
        }
    }

    /**
     * Looks a name up as {@link #resolve} does, from the current scope and module.
     */
    private Lookup lookUp(String name, SourceInfo at) {
        return lookUp(name, at, currentScope);
    }

    /**
     * Looks a name up as {@link #resolve} does, from the given scope, in the placement the
     * position names.
     */
    private Lookup lookUp(String name, SourceInfo at, Scope from) {
        // A token of a placement, in the module's own file or in a file it sources, looks up the
        // symbols of that placement; a token of no registered placement those of its file.
        ModuleScope module = modules.get(at.placement());
        String filedUnder = module != null ? module.aliasChain() : at.fileName();
        return lookUp(name, filedUnder, from, module);
    }

    /**
     * Looks a name up from the given scope. A plain name is searched in the scopes from the given
     * one to the root, under the key the symbols of the namespace are filed by. A dotted name is a
     * path: a first segment that is an alias of the given module's level leads into the placement
     * the alias leads to; any other first segment is searched like a plain name and leads into the
     * scope its symbol opened. The walk then descends segment by segment, as
     * {@link #descendModule} and {@link #descendScope} describe. A symbol found in a scope is
     * qualified by that scope's path, one found on the module level by the module's alias chain.
     *
     * @param name       The name as written.
     * @param filedUnder The key the namespace's symbols are filed under: a placement's alias
     *                   chain, or a file outside a module.
     * @param from       The innermost scope to search, the level the name is written on.
     * @param module     The placement the name is written in; {@code null} outside a module.
     */
    private Lookup lookUp(String name, String filedUnder, Scope from, ModuleScope module) {
        int dot = name.indexOf('.');
        if (dot < 0) {
            Scope scope = enclosingScopeOf(from, name.toUpperCase(), filedUnder);
            if (scope == null) {
                return Lookup.undefined();
            }
            return found(scope, name.toUpperCase(), filedUnder, module);
        }
        if (dot == 0) {
            return Lookup.undefined();
        }
        // Explanations quote the segments as the program wrote them; lookups use the key
        String first = name.substring(0, dot);
        String remainder = name.substring(dot + 1);
        String firstKey = first.toUpperCase();

        // An alias of the module level leads into the module it names; any other name the
        // scopes hold leads into the scope its symbol opened.
        String targetAliasChain = module != null ? module.aliases().get(firstKey) : null;
        Scope holder = enclosingScopeOf(from, firstKey, filedUnder);
        if (targetAliasChain != null && (holder == null || holder == rootScope)) {
            return descendModule(targetAliasChain, first, remainder, from);
        }
        if (holder != null) {
            Symbol symbol = holder.symbols.get(firstKey).get(filedUnder);
            // An alias symbol of the module level without an entry in the alias map leads nowhere;
            // the table cannot tell why, so it says only that.
            if (holder == rootScope && symbol.node() instanceof IPlacementAlias) {
                return Lookup.missing("'" + first + "' leads to no module.");
            }
            return descendInto(symbol, first, remainder, filedUnder, from, module);
        }
        if (module == null) {
            return Lookup.undefined();
        }
        return Lookup.missing("'" + first + "' is neither a name nor an alias of this module.");
    }

    /**
     * Finds the scope that holds a name for the given namespace, from the given scope outward.
     *
     * @param from       The innermost scope to search.
     * @param key        The upper-cased name.
     * @param filedUnder The key the namespace's symbols are filed under.
     * @return The innermost scope holding the name, or {@code null} if none does.
     */
    private static Scope enclosingScopeOf(Scope from, String key, String filedUnder) {
        for (Scope scope = from; scope != null; scope = scope.parent) {
            Map<String, Symbol> perFile = scope.symbols.get(key);
            if (perFile != null && perFile.containsKey(filedUnder)) {
                return scope;
            }
        }
        return null;
    }

    /**
     * Builds the result for a symbol found in a scope: qualified by the scope's path, or on the
     * module level by the placement's alias chain.
     *
     * @param scope      The scope that holds the symbol.
     * @param key        The upper-cased name.
     * @param filedUnder The key the symbol is filed under in the scope.
     * @param module     The placement the scope belongs to; {@code null} outside a module.
     */
    private Lookup found(Scope scope, String key, String filedUnder, ModuleScope module) {
        String qualified = QualifiedNames.join(
                scope == rootScope ? (module != null ? module.aliasChain() : null) : scope.name(), key);
        return new Lookup(new ResolvedSymbol(scope.symbols.get(key).get(filedUnder), qualified, scope.name()), scope);
    }

    /**
     * Continues a path after a segment that names something other than a module: a symbol whose
     * node opens a scope is a level the walk descends into; any other symbol has no members.
     *
     * @param symbol     The symbol the segment names.
     * @param level      The path up to and including the segment, as the program wrote it.
     * @param remainder  The rest of the path, as the program wrote it.
     * @param filedUnder The key the namespace's symbols are filed under.
     * @param from       The scope the path is written in.
     * @param module     The placement the symbol belongs to; {@code null} outside a module.
     */
    private Lookup descendInto(Symbol symbol, String level, String remainder, String filedUnder, Scope from, ModuleScope module) {
        Scope inner = getNodeScope(symbol.node());
        if (inner == null) {
            return Lookup.missing("'" + level + "' has no member '" + firstSegment(remainder) + "'.");
        }
        return descendScope(inner, level, remainder, filedUnder, from, module);
    }

    /**
     * Resolves the rest of a path inside a scope a symbol opened, one segment at a time. The
     * segment is looked up in that scope alone. If the writer does not stand inside the scope,
     * that is, if it is neither the scope the path is written in nor one enclosing it, the
     * segment's symbol has to be exported.
     *
     * @param level      The scope to look the next segment up in.
     * @param levelName  The path that led to the scope, as the program wrote it.
     * @param remainder  The rest of the path, as the program wrote it.
     * @param filedUnder The key the namespace's symbols are filed under.
     * @param from       The scope the path is written in.
     * @param module     The placement the scope belongs to; {@code null} outside a module.
     * @return The symbol with its qualified name, the placement and the scope that hold it, or
     *         the reason there is none.
     */
    private Lookup descendScope(Scope level, String levelName, String remainder, String filedUnder, Scope from, ModuleScope module) {
        String segment = firstSegment(remainder);
        String key = segment.toUpperCase();
        Map<String, Symbol> perFile = level.symbols.get(key);
        Symbol symbol = perFile != null ? perFile.get(filedUnder) : null;
        if (symbol == null) {
            return Lookup.missing("'" + levelName + "' has no member '" + segment + "'.");
        }
        if (!symbol.exported() && !encloses(level, from)) {
            return Lookup.missing("'" + segment + "' of " + levelName + " is not marked EXPORT.");
        }
        if (segment.length() == remainder.length()) {
            return found(level, key, filedUnder, module);
        }
        return descendInto(symbol, levelName + "." + segment, remainder.substring(segment.length() + 1), filedUnder, from, module);
    }

    /**
     * Resolves the rest of a path inside a module, one segment at a time. The writer never stands
     * inside a module it reaches through an alias, so every segment found there has to be
     * exported. A segment that is an alias of the module's level leads into the placement the
     * alias leads to, if the alias symbol is exported; a segment that names a symbol opening a
     * scope leads into that scope.
     *
     * @param currentChain The alias chain of the module the remainder is looked up in.
     * @param moduleName   How the program names that module, for the explanation of a failure.
     * @param remainder    The rest of the path, as the program wrote it.
     * @param from         The scope the path is written in.
     * @return The symbol with its qualified name, the placement and the scope that hold it, or
     *         the reason there is none.
     */
    private Lookup descendModule(String currentChain, String moduleName, String remainder, Scope from) {
        ModuleScope modScope = modules.get(currentChain);
        if (modScope == null) {
            return Lookup.missing(moduleName + " is not a module of this compilation.");
        }
        String segment = firstSegment(remainder);
        String key = segment.toUpperCase();
        boolean last = segment.length() == remainder.length();
        Symbol symbol = modScope.symbols().get(key);
        if (symbol == null) {
            return Lookup.missing("'" + moduleName + "' has no member '" + segment + "'.");
        }
        if (!symbol.exported()) {
            String kind = modScope.aliases().containsKey(key) ? "alias '" : "'";
            return Lookup.missing(kind + segment + "' of " + moduleName + " is not marked EXPORT.");
        }
        if (last) {
            return new Lookup(new ResolvedSymbol(symbol, QualifiedNames.join(currentChain, key), rootScope.name()), rootScope);
        }
        String nextRemainder = remainder.substring(segment.length() + 1);
        String level = moduleName + "." + segment;
        String nextChain = modScope.aliases().get(key);
        if (nextChain != null) {
            return descendModule(nextChain, level, nextRemainder, from);
        }
        return descendInto(symbol, level, nextRemainder, modScope.aliasChain(), from, modScope);
    }

    /**
     * Returns the first segment of a path.
     *
     * @param path The path, as the program wrote it.
     * @return The text before the first dot, or the whole path if it has none.
     */
    private static String firstSegment(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    /**
     * Tells whether the writer stands inside a scope: whether it is the given scope or one
     * enclosing it. Scopes are compared by reference.
     *
     * @param scope The scope a path leads into.
     * @param from  The scope the path is written in.
     * @return {@code true} if {@code scope} is {@code from} or one of its ancestors.
     */
    private static boolean encloses(Scope scope, Scope from) {
        for (Scope s = from; s != null; s = s.parent) {
            if (s == scope) {
                return true;
            }
        }
        return false;
    }
}
