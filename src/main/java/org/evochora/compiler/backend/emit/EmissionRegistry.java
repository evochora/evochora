package org.evochora.compiler.backend.emit;

import org.evochora.compiler.backend.emit.features.CallBindingCaptureRule;
import org.evochora.compiler.backend.emit.features.ProcedureMarshallingRule;
import org.evochora.compiler.backend.emit.features.CallerMarshallingRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry for emission rules applied in order.
 */
public final class EmissionRegistry {

	private final List<IEmissionRule> rules = new ArrayList<>();

	public void register(IEmissionRule rule) { rules.add(rule); }
	public List<IEmissionRule> rules() { return rules; }

	public static EmissionRegistry initializeWithDefaults() {
		EmissionRegistry reg = new EmissionRegistry();
        reg.register(new CallBindingCaptureRule());
		reg.register(new ProcedureMarshallingRule());
		reg.register(new CallerMarshallingRule());
		return reg;
	}
}


