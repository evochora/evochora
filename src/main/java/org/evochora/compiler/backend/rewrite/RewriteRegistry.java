package org.evochora.compiler.backend.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of the IR rewriting phase: the rules, in the order they are applied.
 */
public final class RewriteRegistry {

	private final List<IRewriteRule> rules = new ArrayList<>();

	/**
	 * Registers a rule, after those registered before it.
	 * @param rule The rule to register.
	 */
	public void register(IRewriteRule rule) { rules.add(rule); }

	/**
	 * Registers all rules from the given list, in list order.
	 * @param rules The rules to register.
	 */
	public void registerAll(List<IRewriteRule> rules) { this.rules.addAll(rules); }

	/**
	 * Returns the rules in registration order. Each rule rewrites the whole IR item list
	 * in turn, so a later rule operates on the output of the earlier ones.
	 *
	 * @return The list of registered rules.
	 */
	public List<IRewriteRule> rules() { return Collections.unmodifiableList(rules); }
}
