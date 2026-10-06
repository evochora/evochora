package org.evochora.compiler.model.symbols;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.IIdentifierBinding;
import org.evochora.compiler.model.ast.IdentifierNode;

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
 * Supports nested procedure scopes within each module, qualified cross-module name resolution
 * via import aliases, and export-based visibility control.
 *
 * <p>Modules are identified by their import alias chain (e.g., "PRED.MATH") rather than
 * by file path, allowing the same physical file to appear as distinct placements with
 * independent symbol namespaces.</p>
 *
 * <p>For single-file compilations, the table operates with a single default module, so a
 * caller that never imports anything need not name a module at all.</p>
 *
 * <p>The table enforces two rules on names itself, whatever they name: a name is one segment, so
 * a definition whose name contains a dot is reported when it is defined; and a name that a scope
 * enclosing the definition already holds is reported when the table freezes, once all
 * definitions of both passes are in.</p>
 */
public class SymbolTable {

    /**
     * Represents a single scope in the symbol table (procedure-local or module-global).
     * The root scope, the module level of every placement, is named {@link TokenInfo#GLOBAL_SCOPE};
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
         * @return the scope name, {@link TokenInfo#GLOBAL_SCOPE} for the root scope or the path of
         *         a procedure, e.g. "MAIN.INIT"
         */
        public String name() {
            return name;
        }
    }

    // --- Module-aware primary structure (keyed by alias chain) ---
    private final Map<String, ModuleScope> modules = new HashMap<>();
    private String currentAliasChain;

    // --- Procedure-local scope hierarchy (within the current module) ---
    private final Scope rootScope;
    private Scope currentScope;

    // Every scope entered below the root, in the order it was entered; the shadowing check of
    // freeze walks them.
    private final List<Scope> scopes = new ArrayList<>();

    // --- Node-to-scope mapping (populated by ProcedureSymbolCollector, consumed by TokenMapGenerator) ---
    // Keyed by node identity: AST nodes are records, so two structurally equal nodes would
    // otherwise share one entry and therefore one scope.
    private final Map<AstNode, Scope> nodeScopeMap = new IdentityHashMap<>();

    private final DiagnosticsEngine diagnostics;

    /**
     * How many definitions {@link #bindingOf} follows before it gives up; a chain longer than
     * this is a circle it failed to notice, and it returns empty as for one it did.
     */
    private static final int MAX_BINDING_DEPTH = 32;
    private final Set<String> reportedCircles = new HashSet<>();
    private boolean frozen = false;

    /**
     * Constructs a new symbol table. The current module must be set via
     * {@link #setCurrentModule(String)} before any define/resolve operations.
     * @param diagnostics The diagnostics engine for reporting errors.
     */
    public SymbolTable(DiagnosticsEngine diagnostics) {
        this.diagnostics = diagnostics;
        this.rootScope = new Scope(null, TokenInfo.GLOBAL_SCOPE);
        this.currentScope = this.rootScope;
    }

    // === Freeze support ===

    /**
     * Freezes the symbol table, preventing structural modifications (define, registerModule,
     * enterScope, registerNodeScope). Cursor operations (setCurrentScope, leaveScope,
     * resetScope) and all reads remain allowed.
     * <p>
     * Before the table closes, every scope entered below the root is compared with the scopes
     * enclosing it: a name that an enclosing scope holds for the same module placement, or
     * outside a module for the same file, is reported at the inner definition, naming the
     * position of the enclosing one. A name never means two things depending on the level it is
     * written on. The check runs here because definitions arrive in two passes and in text
     * order, so only the complete table shows every pair; it runs once, on the first call.
     * <p>
     * {@link #setCurrentModule(String)} remains allowed only for modules that are already
     * registered. Switching to an unknown alias chain has to create a scope for it and
     * therefore fails on a frozen table.
     */
    public void freeze() {
        if (!frozen) {
            reportShadowing();
        }
        this.frozen = true;
        modules.values().forEach(ModuleScope::freeze);
    }

    /**
     * Reports every name of a scope below the root that a scope enclosing it holds under the
     * same key, the module placement or, outside a module, the file. The nearest enclosing
     * definition is named; scopes are visited in the order they were entered and names in the
     * order they were defined.
     */
    private void reportShadowing() {
        for (Scope scope : scopes) {
            for (Map.Entry<String, Map<String, Symbol>> byName : scope.symbols.entrySet()) {
                for (Map.Entry<String, Symbol> byKey : byName.getValue().entrySet()) {
                    Symbol enclosing = enclosingDefinition(scope.parent, byName.getKey(), byKey.getKey());
                    if (enclosing != null) {
                        Symbol inner = byKey.getValue();
                        diagnostics.reportError(
                                "'" + inner.name() + "' is already defined at " + SourceInfo.position(enclosing.sourceInfo())
                                        + ", on an enclosing level.",
                                inner.sourceInfo().fileName(), inner.sourceInfo().lineNumber());
                    }
                }
            }
        }
    }

    /**
     * Finds the definition of a name in the given scope or the nearest one enclosing it.
     *
     * @param from The innermost scope to search.
     * @param name The upper-cased name.
     * @param key  The module placement, or the file outside a module, the name is filed under.
     * @return The definition, or {@code null} if no scope from {@code from} outward holds one.
     */
    private static Symbol enclosingDefinition(Scope from, String name, String key) {
        for (Scope scope = from; scope != null; scope = scope.parent) {
            Map<String, Symbol> perFile = scope.symbols.get(name);
            if (perFile != null && perFile.containsKey(key)) {
                return perFile.get(key);
            }
        }
        return null;
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
        String path = currentScope == rootScope ? qualify(currentAliasChain, key) : currentScope.name + "." + key;
        Scope newScope = new Scope(currentScope, path);
        scopes.add(newScope);
        currentScope = newScope;
        return newScope;
    }

    /**
     * Qualifies a name defined on the module level with the alias chain of its placement.
     *
     * @param aliasChain The alias chain; {@code null} or empty for none.
     * @param key        The upper-cased name.
     * @return The chain and the name joined by a dot, or the name alone without a chain.
     */
    private static String qualify(String aliasChain, String key) {
        return aliasChain != null && !aliasChain.isEmpty() ? aliasChain + "." + key : key;
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
     * Associates an AST node with its scope. Called by ProcedureSymbolCollector as it walks
     * the AST and discovers the procedures that open a scope.
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

    // === Symbol definition and resolution ===

    /**
     * Defines a new symbol in the current scope and registers it in the current module scope.
     * A name that the same module placement, or outside a module the same file, has defined in
     * this scope already keeps its first definition;
     * the caller, which knows what kind of thing it tried to define, reports that.
     * <p>
     * A name is one segment: a name that contains a dot could only be confused with the path of
     * a name on another level. Such a definition is reported here, at the symbol's position,
     * whatever kind of thing it names, and is not filed.
     * @param symbol The symbol to define.
     * @return The definition the name already had in this scope, which stays; empty if the
     *         symbol was defined, or if its name contains a dot and it was reported instead.
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
        String file = symbol.sourceInfo().fileName();

        // In module context, the symbol is filed under the module placement rather than the file
        // it stands in: .SOURCE-included symbols are resolvable from the module's own tokens, and
        // two placements of one file keep apart.
        ModuleScope modScope = modules.get(currentAliasChain);
        if (modScope != null) {
            file = modScope.aliasChain();
        }

        // Register in the scope hierarchy (for procedure-local visibility)
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
     * @return The node the identifier finally stands for: what the last binding binds to, or
     *         the identifier under the qualified name of a symbol that offers no binding (a
     *         label, a procedure). Empty if the identifier names no symbol, or if its
     *         definitions go in a circle, which is reported once at the definition the circle
     *         was entered through.
     */
    public Optional<AstNode> bindingOf(IdentifierNode reference) {
        IdentifierNode current = reference;
        // The symbols visited, by identity, and the names as written, for the message
        Set<Symbol> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> chain = new ArrayList<>();
        SourceInfo firstDefinition = null;
        // The scope the identifier is looked up from: the current scope for the reference, the
        // scope of the definition for every identifier a definition binds to.
        Scope from = currentScope;
        // The placement the identifier stands in when a definition has led out of the current one;
        // null while it stands in the current placement.
        ModuleScope foreign = null;
        for (int depth = 0; depth < MAX_BINDING_DEPTH; depth++) {
            Lookup lookup = foreign == null
                    ? lookUp(current.text(), current.sourceInfo(), from)
                    : lookUp(current.text(), foreign.aliasChain(), from, foreign);
            Optional<ResolvedSymbol> resolved = lookup.resolution().found();
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            if (!(resolved.get().symbol().node() instanceof IIdentifierBinding binding)) {
                return Optional.of(new IdentifierNode(resolved.get().qualifiedName(), current.sourceInfo()));
            }
            if (visited.contains(resolved.get().symbol())) {
                if (reportedCircles.add(resolved.get().symbol().sourceInfo() + " " + resolved.get().symbol().name())) {
                    diagnostics.reportError(
                            "Definition of " + chain.get(0) + " is circular: " + String.join(" -> ", chain) + " -> " + current.text() + ".",
                            firstDefinition != null ? firstDefinition.fileName() : "unknown",
                            firstDefinition != null ? firstDefinition.lineNumber() : 0);
                }
                return Optional.empty();
            }
            visited.add(resolved.get().symbol());
            chain.add(current.text());
            if (firstDefinition == null) {
                firstDefinition = resolved.get().symbol().sourceInfo();
            }
            // The definition is written in the scope and the placement that hold it, so the
            // identifier it binds to is looked up there.
            foreign = lookup.placement() == modules.get(currentAliasChain) ? null : lookup.placement();
            from = lookup.scope();
            AstNode bound = binding.bind(current);
            if (!(bound instanceof IdentifierNode next)) {
                return Optional.of(bound);
            }
            current = next;
        }
        return Optional.empty();
    }

    /**
     * Resolves a symbol by name, searching from the current scope upwards to the root.
     * If the symbol is not found, it attempts to resolve it as a qualified name
     * (e.g., {@code ALIAS.SYMBOL}) using the current module's import aliases.
     * @param name The name of the symbol to resolve.
     * @param at   The position the name is written at. A position in the placement of the
     *             current module looks the name up among that module's symbols, whichever file
     *             of the placement it stands in; any other position looks it up among the
     *             symbols of its file.
     * @return The symbol with its qualified name, or the reason there is none.
     */
    public Resolution resolve(String name, SourceInfo at) {
        return lookUp(name, at).resolution();
    }

    /**
     * What a lookup found, together with the module placement and the scope the symbol belongs to.
     *
     * @param resolution The symbol with its qualified name, or the reason there is none.
     * @param placement  The placement whose namespace holds the symbol; {@code null} if nothing
     *                   was found or no module is current.
     * @param scope      The scope the symbol is defined in; {@code null} if nothing was found.
     */
    private record Lookup(Resolution resolution, ModuleScope placement, Scope scope) {
        static Lookup missing(String explanation) {
            return new Lookup(new Resolution.Missing(explanation), null, null);
        }
    }

    /**
     * Looks a name up as {@link #resolve} does, from the current scope and module.
     */
    private Lookup lookUp(String name, SourceInfo at) {
        return lookUp(name, at, currentScope);
    }

    /**
     * Looks a name up as {@link #resolve} does, in the current module, from the given scope.
     */
    private Lookup lookUp(String name, SourceInfo at, Scope from) {
        // A token of the current placement, in the module's own file or in a file it sources,
        // looks up the symbols of the current placement.
        ModuleScope currentModScope = modules.get(currentAliasChain);
        String filedUnder = currentModScope != null && currentModScope.aliasChain().equals(at.placement())
                ? currentModScope.aliasChain()
                : at.fileName();
        return lookUp(name, filedUnder, from, currentModScope);
    }

    /**
     * Looks a name up in the scopes from the given one to the root, under the key the symbols of
     * the namespace are filed by, and then as a qualified name through the imports and the
     * requirements of the given module. A symbol found in a scope is qualified by that scope's
     * path, one found on the module level by the module's alias chain.
     *
     * @param name       The name as written.
     * @param filedUnder The key the namespace's symbols are filed under: a placement's alias
     *                   chain, or a file outside a module.
     * @param from       The innermost scope to search.
     * @param module     The placement the name is written in; {@code null} outside a module.
     */
    private Lookup lookUp(String name, String filedUnder, Scope from, ModuleScope module) {
        String key = name.toUpperCase();

        // Search scope hierarchy (given scope → root)
        for (Scope scope = from; scope != null; scope = scope.parent) {
            Map<String, Symbol> perFile = scope.symbols.get(key);
            if (perFile != null && perFile.containsKey(filedUnder)) {
                String qualified = scope == rootScope
                        ? qualify(module != null ? module.aliasChain() : null, key)
                        : scope.name() + "." + key;
                return new Lookup(new ResolvedSymbol(perFile.get(filedUnder), qualified, scope.name()), module, scope);
            }
        }

        // Attempt qualified name resolution (ALIAS.SYMBOL or multi-level ALIAS.B.SYMBOL)
        int dot = key.indexOf('.');
        if (dot <= 0) {
            return Lookup.missing("the name is not defined.");
        }
        // Explanations quote the segments as the program wrote them; lookups use the key
        String alias = name.substring(0, dot);
        String remainder = name.substring(dot + 1);

        if (module == null) {
            return Lookup.missing("the name is not defined.");
        }
        String targetAliasChain = module.imports().get(alias.toUpperCase());
        if (targetAliasChain == null) {
            targetAliasChain = module.usingBindings().get(alias.toUpperCase());
        }
        if (targetAliasChain == null) {
            return Lookup.missing("'" + alias + "' is neither an import nor a requirement of this module.");
        }
        return resolveMultiLevel(targetAliasChain, alias, remainder);
    }

    /**
     * Resolves the rest of a qualified name inside a module, one segment at a time. Every step
     * but the last leads through an import the module marked with EXPORT. A module's
     * requirements are deliberately not steps: a requirement is satisfied by the importer, who
     * therefore already has a name for that module and does not reach it through the module it
     * handed it to.
     *
     * @param currentChain The alias chain of the module the remainder is looked up in.
     * @param moduleName   How the program names that module, for the explanation of a failure.
     * @param remainder    The rest of the qualified name, as the program wrote it.
     * @return The symbol with its qualified name and the placement that defines it, or the
     *         reason there is none.
     */
    private Lookup resolveMultiLevel(String currentChain, String moduleName, String remainder) {
        ModuleScope modScope = modules.get(currentChain);
        if (modScope == null) {
            return Lookup.missing(moduleName + " is not a module of this compilation.");
        }
        int dot = remainder.indexOf('.');
        if (dot <= 0) {
            String symbolKey = remainder.toUpperCase();
            Symbol sym = modScope.symbols().get(symbolKey);
            if (sym == null) {
                return Lookup.missing(moduleName + " has no symbol '" + remainder + "'.");
            }
            if (!isExported(sym)) {
                return Lookup.missing("'" + remainder + "' of " + moduleName + " is not marked EXPORT.");
            }
            return new Lookup(new ResolvedSymbol(sym, qualify(currentChain, symbolKey), rootScope.name()), modScope, rootScope);
        }

        String nextAlias = remainder.substring(0, dot);
        String nextRemainder = remainder.substring(dot + 1);

        String nextChain = modScope.imports().get(nextAlias.toUpperCase());
        if (nextChain == null) {
            if (modScope.requires().containsKey(nextAlias.toUpperCase())) {
                return Lookup.missing("'" + nextAlias + "' is a requirement of " + moduleName
                        + "; use the module you supplied for it.");
            }
            return Lookup.missing("'" + nextAlias + "' is not an import of " + moduleName + ".");
        }
        if (!Boolean.TRUE.equals(modScope.importExported().get(nextAlias.toUpperCase()))) {
            return Lookup.missing("import '" + nextAlias + "' of " + moduleName + " is not marked EXPORT.");
        }
        return resolveMultiLevel(nextChain, moduleName + "." + nextAlias, nextRemainder);
    }

    /**
     * Checks if a symbol is exported (visible to other modules).
     */
    private boolean isExported(Symbol sym) {
        return sym.exported();
    }
}
