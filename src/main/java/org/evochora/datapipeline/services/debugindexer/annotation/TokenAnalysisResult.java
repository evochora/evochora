package org.evochora.datapipeline.services.debugindexer.annotation;

import org.evochora.datapipeline.services.debugindexer.annotation.enums.TokenType;

/**
 * Represents the result of analyzing a token for annotation purposes.
 * Contains all the information needed to create an annotation.
 */
public record TokenAnalysisResult(
    String token,                    // The original token
    TokenType type,                  // The classification of the token
    String annotationText,           // The annotation text to display (e.g., "12|1", "=DATA:42") - frontend adds brackets
    String kind                      // The kind of annotation (maps to which handler was used)
) {}
