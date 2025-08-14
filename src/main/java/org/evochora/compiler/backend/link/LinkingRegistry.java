package org.evochora.compiler.backend.link;

import org.evochora.compiler.backend.link.features.LabelRefLinkingRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry for linking rules applied in order.
 */
public final class LinkingRegistry {

	private final List<ILinkingRule> rules = new ArrayList<>();

	public void register(ILinkingRule rule) { rules.add(rule); }
	public List<ILinkingRule> rules() { return rules; }

	public static LinkingRegistry initializeWithDefaults() {
		LinkingRegistry reg = new LinkingRegistry();
		reg.register(new LabelRefLinkingRule());
		return reg;
	}
}


