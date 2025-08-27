package org.evochora.compiler.backend.link;

import org.evochora.compiler.backend.link.features.LabelRefLinkingRule;
import org.evochora.compiler.frontend.semantics.SymbolTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry for linking rules, which are applied in order to each instruction.
 */
public class LinkingRegistry {

    private final List<ILinkingRule> rules = new ArrayList<>();

    /**
     * Registers a new linking rule.
     * @param rule The rule to register.
     */
    public void register(ILinkingRule rule) { rules.add(rule); }

    /**
     * @return The list of registered linking rules.
     */
    public List<ILinkingRule> rules() { return rules; }

    /**
     * Initializes a new linking registry with the default rules.
     * @param symbolTable The symbol table for resolving symbols.
     * @return A new registry with default rules.
     */
    public static LinkingRegistry initializeWithDefaults(SymbolTable symbolTable) {
        LinkingRegistry reg = new LinkingRegistry();
        reg.register(new LabelRefLinkingRule(symbolTable));
        return reg;
    }
}