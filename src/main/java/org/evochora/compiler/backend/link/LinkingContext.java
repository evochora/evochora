package org.evochora.compiler.backend.link;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable context for the linking phase.
 */
public final class LinkingContext {

	private int linearAddressCursor = 0;
	private final Map<Integer, int[]> callSiteBindings = new HashMap<>();

	public int nextAddress() { return linearAddressCursor++; }
	public int currentAddress() { return linearAddressCursor; }

	public Map<Integer, int[]> callSiteBindings() { return callSiteBindings; }
}


