package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.SourceInfo;

/**
 * Marker interface for all IR elements emitted by the frontend and
 * consumed by backend phases. Every item must carry source information
 * for diagnostics and debugging.
 */
public interface IrItem {
	/**
	 * Returns the source location this item is attributed to, which is what lets
	 * emitted machine code and diagnostics point back at a line of source.
	 *
	 * @return the source information carried by this item
	 */
	SourceInfo source();
}


