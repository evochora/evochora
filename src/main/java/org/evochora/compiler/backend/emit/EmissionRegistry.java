package org.evochora.compiler.backend.emit;

import org.evochora.compiler.backend.emit.features.CallBindingCaptureRule;
import org.evochora.compiler.backend.emit.features.ProcedureMarshallingRule;
import org.evochora.compiler.backend.emit.features.CallerMarshallingRule;
import org.evochora.compiler.backend.emit.features.RefValBindingCaptureRule;

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
	 * @return The list of registered emission rules.
	 */
	public List<IEmissionRule> rules() { return rules; }

	/**
	 * Initializes a new emission registry with the default rules.
	 * @return A new registry with default rules.
	 */
	public static EmissionRegistry initializeWithDefaults() {
		EmissionRegistry reg = new EmissionRegistry();
        reg.register(new CallBindingCaptureRule());
        reg.register(new RefValBindingCaptureRule());
		reg.register(new ProcedureMarshallingRule());
		reg.register(new CallerMarshallingRule());
		return reg;
	}
}


