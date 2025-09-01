package org.evochora.server.indexer.annotation;

/**
 * Represents a single token annotation.
 * Used by both source view and next instruction display.
 * 
 * The annotationText contains the content without brackets (e.g., "12|1", "=DATA:42")
 * which will be inserted after the token in the display. The frontend adds the brackets.
 */
public record TokenAnnotation(
    String token,           // The token being annotated
    String annotationText,  // The annotation text (e.g., "[12|1]", "[=DATA:42]")
    String kind            // The kind of annotation (e.g., "label", "reg", "param", "call", "ret")
) {}
