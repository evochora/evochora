package org.evochora.compiler.backend.link;

import org.evochora.compiler.backend.link.features.LabelRefLinkingRule;
import org.evochora.compiler.frontend.semantics.SymbolTable; // NEU

import java.util.ArrayList;
import java.util.List;

public class LinkingRegistry {

    private final List<ILinkingRule> rules = new ArrayList<>();

    public void register(ILinkingRule rule) { rules.add(rule); }
    public List<ILinkingRule> rules() { return rules; }

    public static LinkingRegistry initializeWithDefaults(SymbolTable symbolTable) {
        LinkingRegistry reg = new LinkingRegistry();
        reg.register(new LabelRefLinkingRule(symbolTable));
        return reg;
    }
}