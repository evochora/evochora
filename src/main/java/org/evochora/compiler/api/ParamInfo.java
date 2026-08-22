package org.evochora.compiler.api;

/**
 * Represents a single procedure parameter with its name and type.
 * This is used in the ProgramArtifact to store parameter information
 * for each procedure, including whether parameters are REF, VAL, LREF, or LVAL.
 *
 * @param name The parameter name (e.g., "PROC1REG1").
 * @param type The parameter type (REF, VAL, LREF, or LVAL).
 */
public record ParamInfo(String name, ParamType type) {
}
