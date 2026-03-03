package org.evochora.compiler.frontend.module;

/**
 * Carried by PUSH_CTX tokens to propagate module placement information through the token stream.
 *
 * @param sourcePath  The resolved absolute path of the included file.
 * @param aliasChain  The dot-separated import alias chain (e.g., "PRED.MATH") for .IMPORT placements,
 *                    or {@code null} for .SOURCE text inclusions (which inherit the parent's alias chain).
 */
public record PlacementContext(String sourcePath, String aliasChain) {}
