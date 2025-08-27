package org.evochora.compiler.frontend;

/**
 * Defines the different phases of the compilation process.
 * Handlers can register for one of these phases.
 */
public enum CompilerPhase {
    /**
     * Phase 0: Processes directives that change the structure of the source files,
     * such as <code>.include</code> or <code>.macro</code>.
     */
    PREPROCESSING,

    /**
     * Phase 1: Processes most directives and builds the AST.
     */
    PARSING
}