package org.evochora.cli.cleanup;

/**
 * Result of a database compaction attempt.
 *
 * @param success whether compaction was successful
 * @param message description of result or reason for failure
 */
public record CompactionResult(boolean success, String message) {}
