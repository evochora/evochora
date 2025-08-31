package org.evochora.compiler.api;

/**
 * A pure data class representing a position in the source code.
 * It is part of the public compiler API and free of implementation details.
 *
 * @param fileName The file where the code is located.
 * @param lineNumber The line number.
 * @param lineContent The content of the line.
 */
public record SourceInfo(String fileName, int lineNumber, int columnNumber, String lineContent) {}
