package org.evochora.compiler.ir;

import org.evochora.compiler.api.SourceInfo;

/**
 * Marker interface for all IR elements emitted by the frontend and
 * consumed by backend phases. Every item must carry source information
 * for diagnostics and debugging.
 */
public interface IrItem {
	SourceInfo source();
}


