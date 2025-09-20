package org.evochora.datapipeline.api.contracts;

/**
 * Serializable version of SourceInfo for datapipeline API.
 * Contains the same information as the compiler API SourceInfo.
 *
 * @param fileName The file where the code is located
 * @param lineNumber The line number
 * @param columnNumber The column number
 */
public record SerializableSourceInfo(
    String fileName,
    int lineNumber,
    int columnNumber
) {}