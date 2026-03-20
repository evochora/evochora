package org.evochora.compiler.backend.emit;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry for emission rules applied in order.
 */
public final class EmissionRegistry {

	private final List<IEmissionRule> rules = new ArrayList<>();

	/**
	 * Registers a new emission rule.
	 * @param rule The rule to register.
	 */
	public void register(IEmissionRule rule) { rules.add(rule); }

	/**
	 * Registers all emission rules from the given list.
	 * @param rules The rules to register.
	 */
	public void registerAll(List<IEmissionRule> rules) { this.rules.addAll(rules); }

	/**
	 * @return The list of registered emission rules.
	 */
	public List<IEmissionRule> rules() { return rules; }
}
