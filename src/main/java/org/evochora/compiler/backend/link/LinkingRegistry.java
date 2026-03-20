package org.evochora.compiler.backend.link;

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
     * Registers all linking rules from the given list.
     * @param rules The rules to register.
     */
    public void registerAll(List<ILinkingRule> rules) { this.rules.addAll(rules); }

    /**
     * @return The list of registered linking rules.
     */
    public List<ILinkingRule> rules() { return rules; }
}
