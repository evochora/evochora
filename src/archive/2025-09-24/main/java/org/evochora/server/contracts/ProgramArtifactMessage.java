package org.evochora.server.contracts;

import org.evochora.compiler.api.ProgramArtifact;

/**
 * Message carrying a compiled ProgramArtifact to be persisted.
 *
 * @param programId     unique identifier for the program
 * @param programArtifact ProgramArtifact instance
 */
public record ProgramArtifactMessage(String programId, ProgramArtifact programArtifact) implements IQueueMessage {
}


