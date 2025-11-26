package org.evochora.node.processes.http.api.pipeline.dto;

/**
 * Response DTO for simple message responses (e.g., "Request accepted").
 * <p>
 * Used for lifecycle commands (start, stop, pause, resume, restart) that return
 * a simple acknowledgment message.
 *
 * @param message The response message
 */
public record MessageResponseDto(
    String message
) {}

